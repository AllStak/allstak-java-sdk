package dev.allstak.spring;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end integration test proving that the AllStak Spring Boot starter
 * performs REAL auto-instrumentation — no manual helper calls anywhere in
 * the application code under test.
 *
 * <p>Three outbound WireMock servers are started:
 * <ul>
 *   <li><b>ingest</b> — the AllStak backend; every assertion about what the
 *       SDK sent is made against this server.</li>
 *   <li><b>downstream</b> — a simulated downstream API that the application
 *       calls via RestTemplate, WebClient, and other paths. The SDK must
 *       capture these calls as <code>direction="outbound"</code> entries
 *       WITHOUT the application code calling any AllStak helper.</li>
 * </ul>
 *
 * <p>The application code in {@link AutoInstrumentedApp} contains:
 * <ul>
 *   <li>A {@code RestTemplate} bean built the idiomatic way (via
 *       {@code RestTemplateBuilder}) — MUST be auto-instrumented.</li>
 *   <li>A second {@code RestTemplate} bean created via
 *       {@code new RestTemplate()} — MUST be auto-instrumented.</li>
 *   <li>A {@code WebClient} bean built via {@code WebClient.Builder} —
 *       MUST be auto-instrumented.</li>
 *   <li>A {@code @Scheduled} method that makes NO AllStak helper calls —
 *       MUST emit a cron heartbeat automatically.</li>
 *   <li>A failing {@code @Scheduled} method — MUST emit a "failed"
 *       heartbeat AND re-raise the exception.</li>
 * </ul>
 *
 * <p>Each test checks that:
 * <ol>
 *   <li>Events are emitted without any {@code AllStak.*} calls in the code
 *       under test.</li>
 *   <li>No duplicate events fire for a single operation.</li>
 *   <li>Failures do not crash the host application.</li>
 * </ol>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = {
                AutoInstrumentationIntegrationTest.AutoInstrumentedApp.class,
                AutoInstrumentationIntegrationTest.WireMockTransportConfig.class
        }
)
class AutoInstrumentationIntegrationTest {

    private static WireMockServer ingest;
    private static WireMockServer downstream;

    @Autowired
    private RestTemplate builderRestTemplate;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("rawRestTemplate")
    private RestTemplate rawRestTemplate;

    @Autowired
    private WebClient webClient;

    @Autowired
    private ScheduledHitCounter hitCounter;

    @Autowired
    private RabbitProbe rabbitProbe;

    @Autowired
    private AsyncProbe asyncProbe;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @BeforeAll
    static void startServers() {
        ingest = new WireMockServer(wireMockConfig().dynamicPort());
        ingest.start();
        ingest.stubFor(post(urlPathMatching("/ingest/v1/.*"))
                .willReturn(aResponse().withStatus(202)
                        .withBody("{\"success\":true,\"data\":{\"id\":\"test-uuid\"}}")));

        downstream = new WireMockServer(wireMockConfig().dynamicPort());
        downstream.start();
        downstream.stubFor(get(urlPathEqualTo("/hello"))
                .willReturn(aResponse().withStatus(200).withBody("{\"greeting\":\"hello\"}")));
        downstream.stubFor(get(urlPathEqualTo("/boom"))
                .willReturn(aResponse().withStatus(500).withBody("kaboom")));
    }

    @AfterAll
    static void stopServers() {
        if (ingest != null) ingest.stop();
        if (downstream != null) downstream.stop();
    }

    @BeforeEach
    void resetIngest() {
        ingest.resetRequests();
        downstream.resetRequests();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("allstak.api-key",          () -> "ask_live_test_key");
        registry.add("allstak.environment",      () -> "auto-test");
        registry.add("allstak.release",          () -> "v1.0.0-auto");
        registry.add("allstak.service-name",     () -> "auto-test");
        registry.add("allstak.flush-interval-ms",() -> "200");
        registry.add("allstak.buffer-size",      () -> "100");
        registry.add("allstak.debug",            () -> "true");
        // Disable the servlet filter for this non-web slice; we are only exercising
        // outbound HTTP + scheduled instrumentation here.
        registry.add("allstak.capture-http-requests", () -> "false");
        // Keep @RabbitListener methods as plain proxied methods in this test; the
        // SDK instrumentation is under test, not broker container startup.
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("spring.rabbitmq.listener.direct.auto-startup", () -> "false");
        registry.add("downstream.url",           () -> "http://localhost:" + downstream.port());
    }

    @TestConfiguration
    static class WireMockTransportConfig {
        @Bean
        public HttpTransport allStakHttpTransport() {
            return new HttpTransport("http://localhost:" + ingest.port(), "ask_live_test_key");
        }
    }

    // ------------------------------------------------------------------ //
    //  RestTemplate — built via RestTemplateBuilder (auto customizer path)
    // ------------------------------------------------------------------ //
    @Test
    void restTemplate_builderPath_isAutoInstrumented() {
        String url = "http://localhost:" + downstream.port() + "/hello";

        // NOTE: no AllStak helper calls anywhere here.
        String body = builderRestTemplate.getForObject(url, String.class);
        assertThat(body).contains("greeting");

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                ingest.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/http-requests"))
                        .withRequestBody(containing("\"direction\":\"outbound\""))
                        .withRequestBody(containing("\"method\":\"GET\""))
                        .withRequestBody(containing("\"path\":\"/hello\""))
                        .withRequestBody(containing("\"statusCode\":200"))));
    }

    // ------------------------------------------------------------------ //
    //  RestTemplate — created via new RestTemplate() (post-processor path)
    // ------------------------------------------------------------------ //
    @Test
    void restTemplate_directConstructionPath_isAutoInstrumented() {
        String url = "http://localhost:" + downstream.port() + "/hello";
        String body = rawRestTemplate.getForObject(url, String.class);
        assertThat(body).contains("greeting");

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                ingest.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/http-requests"))
                        .withRequestBody(containing("\"direction\":\"outbound\""))
                        .withRequestBody(containing("\"path\":\"/hello\""))));
    }

    // ------------------------------------------------------------------ //
    //  WebClient — built via the injected Builder (auto customizer)
    // ------------------------------------------------------------------ //
    @Test
    void webClient_isAutoInstrumented() {
        String body = webClient.get()
                .uri("http://localhost:" + downstream.port() + "/hello")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(3));
        assertThat(body).contains("greeting");

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                ingest.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/http-requests"))
                        .withRequestBody(containing("\"direction\":\"outbound\""))
                        .withRequestBody(containing("\"path\":\"/hello\""))));
    }

    // ------------------------------------------------------------------ //
    //  WebClient — failure path must capture an outbound event AND not crash
    // ------------------------------------------------------------------ //
    @Test
    void webClient_failurePath_doesNotCrashAndStillRecords() {
        // Use an unreachable port so the connect fails.
        Throwable thrown = null;
        try {
            webClient.get()
                    .uri("http://127.0.0.1:1/definitely-not-listening")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(3));
        } catch (Throwable t) {
            thrown = t;
        }
        assertThat(thrown).isNotNull(); // user error surfaced

        // SDK must still emit an outbound record
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                ingest.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/http-requests"))
                        .withRequestBody(containing("\"direction\":\"outbound\""))
                        .withRequestBody(containing("\"errorFingerprint\""))));
    }

    // ------------------------------------------------------------------ //
    //  RestTemplate — failure path must capture an outbound event
    // ------------------------------------------------------------------ //
    @Test
    void restTemplate_failurePath_doesNotCrashAndStillRecords() {
        Throwable thrown = null;
        try {
            rawRestTemplate.getForObject("http://127.0.0.1:1/definitely-not-listening", String.class);
        } catch (Throwable t) {
            thrown = t;
        }
        assertThat(thrown).isNotNull(); // user-visible HTTP failure preserved

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                ingest.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/http-requests"))
                        .withRequestBody(containing("\"direction\":\"outbound\""))
                        .withRequestBody(containing("\"errorFingerprint\""))));
    }

    // ------------------------------------------------------------------ //
    //  RabbitMQ — listener context, spans, and exact exceptions
    // ------------------------------------------------------------------ //
    @Test
    void rabbitListener_successCreatesConsumerSpanWithMessageContext() {
        Message message = MessageBuilder.withBody("{}".getBytes())
                .setHeader(AllStakRabbitSupport.HEADER_TRACE_ID, "trace-rabbit-success")
                .setHeader(AllStakRabbitSupport.HEADER_REQUEST_ID, "req-rabbit-success")
                .setMessageId("message-rabbit-success")
                .setReceivedExchange("orders.events")
                .setReceivedRoutingKey("orders.created")
                .setRedelivered(false)
                .build();

        rabbitProbe.handle(message);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                ingest.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                        .withRequestBody(containing("\"operation\":\"messaging.consumer\""))
                        .withRequestBody(containing("\"traceId\":\"trace-rabbit-success\""))
                        .withRequestBody(containing("\"request.id\":\"req-rabbit-success\""))
                        .withRequestBody(containing("\"messaging.system\":\"rabbitmq\""))
                        .withRequestBody(containing("\"messaging.rabbitmq.routing_key\":\"orders.created\""))));
    }

    @Test
    void rabbitListener_exceptionCreatesConsumerErrorAndRethrows() {
        Message message = MessageBuilder.withBody("{}".getBytes())
                .setHeader(AllStakRabbitSupport.HEADER_TRACE_ID, "trace-rabbit-failure")
                .setHeader(AllStakRabbitSupport.HEADER_REQUEST_ID, "req-rabbit-failure")
                .setMessageId("message-rabbit-failure")
                .setReceivedExchange("orders.events")
                .setReceivedRoutingKey("orders.failed")
                .setRedelivered(true)
                .build();

        Throwable thrown = null;
        try {
            rabbitProbe.fail(message);
        } catch (Throwable t) {
            thrown = t;
        }
        assertThat(thrown).isInstanceOf(IllegalStateException.class);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            ingest.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                    .withRequestBody(containing("spring.rabbit.listener"))
                    .withRequestBody(containing("req-rabbit-failure"))
                    .withRequestBody(containing("rabbit listener boom")));
            ingest.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"operation\":\"messaging.consumer\""))
                    .withRequestBody(containing("\"status\":\"error\""))
                    .withRequestBody(containing("\"traceId\":\"trace-rabbit-failure\""))
                    .withRequestBody(containing("\"messaging.rabbitmq.redelivered\":\"true\"")));
        });
    }

    @Test
    void rabbitTemplate_sendInjectsHeadersAndCreatesProducerSpan() {
        Message message = MessageBuilder.withBody("{}".getBytes()).build();

        rabbitTemplate.send("orders.events", "orders.created", message);

        assertThat(message.getMessageProperties().getHeaders())
                .containsKeys(
                        AllStakRabbitSupport.HEADER_TRACE_ID,
                        AllStakRabbitSupport.HEADER_REQUEST_ID,
                        AllStakRabbitSupport.HEADER_TRACEPARENT);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                ingest.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                        .withRequestBody(containing("\"operation\":\"messaging.producer\""))
                        .withRequestBody(containing("\"messaging.system\":\"rabbitmq\""))
                        .withRequestBody(containing("\"status\":\"ok\""))));
    }

    // ------------------------------------------------------------------ //
    //  @Async — uncaught void-returning method exceptions are captured
    // ------------------------------------------------------------------ //
    @Test
    void asyncMethod_uncaughtExceptionIsCapturedWithoutBlockingCaller() {
        asyncProbe.failAsync();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                ingest.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                        .withRequestBody(containing("spring.async"))
                        .withRequestBody(containing("async boom"))));
    }

    // ------------------------------------------------------------------ //
    //  @Scheduled — success heartbeat is auto-emitted (no helper calls)
    // ------------------------------------------------------------------ //
    @Test
    void scheduledMethod_successEmitsHeartbeatAutomatically() {
        // The @Scheduled method runs on its own every 500 ms.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                ingest.verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo("/ingest/v1/heartbeat"))
                        .withRequestBody(containing("scheduled-hit-counter-tick"))
                        .withRequestBody(containing("\"status\":\"success\""))));

        int hits = hitCounter.getSuccess();
        assertThat(hits).isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------ //
    //  @Scheduled — failing method must emit "failed" and not crash the scheduler
    // ------------------------------------------------------------------ //
    @Test
    void scheduledMethod_failureEmitsFailedHeartbeatAndReraises() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                ingest.verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo("/ingest/v1/heartbeat"))
                        .withRequestBody(containing("scheduled-hit-counter-boom"))
                        .withRequestBody(containing("\"status\":\"failed\""))));

        // The scheduler keeps running after a failed invocation
        assertThat(hitCounter.getFailure()).isGreaterThanOrEqualTo(1);
    }

    // ====================================================================
    //   Application under test — NO AllStak.* CALLS ANYWHERE.
    //   If these methods start capturing, it's proof of auto-instrumentation.
    // ====================================================================

    @SpringBootApplication
    @EnableScheduling
    @EnableAsync
    static class AutoInstrumentedApp {

        @Bean
        public RestTemplate builderRestTemplate(RestTemplateBuilder builder) {
            return builder.build();
        }

        @Bean(name = "rawRestTemplate")
        public RestTemplate rawRestTemplate() {
            return new RestTemplate();
        }

        @Bean
        public WebClient webClient(WebClient.Builder builder) {
            return builder.build();
        }

        @Bean
        public ScheduledHitCounter scheduledHitCounter() {
            return new ScheduledHitCounter();
        }

        @Bean
        public RabbitProbe rabbitProbe() {
            return new RabbitProbe();
        }

        @Bean
        public RabbitTemplate rabbitTemplate() {
            return new CapturingRabbitTemplate();
        }

        @Bean
        public AsyncProbe asyncProbe() {
            return new AsyncProbe();
        }
    }

    /**
     * A bean with {@code @Scheduled} methods that do NOT call any AllStak API.
     * The fact that heartbeats appear in the ingest WireMock is the whole
     * point of this test.
     */
    @Component
    public static class ScheduledHitCounter {
        private final AtomicInteger success = new AtomicInteger();
        private final AtomicInteger failure = new AtomicInteger();

        @Scheduled(fixedDelay = 500, initialDelay = 200)
        public void tick() {
            success.incrementAndGet();
        }

        @Scheduled(fixedDelay = 500, initialDelay = 400)
        public void boom() {
            failure.incrementAndGet();
            throw new IllegalStateException("scheduled boom");
        }

        public int getSuccess() { return success.get(); }
        public int getFailure() { return failure.get(); }
    }

    public static class RabbitProbe {
        @RabbitListener(queues = "orders.audit")
        public void handle(Message message) {
            // no-op; instrumentation should create the consumer span
        }

        @RabbitListener(queues = "orders.audit")
        public void fail(Message message) {
            throw new IllegalStateException("rabbit listener boom");
        }
    }

    public static class AsyncProbe {
        @Async
        public void failAsync() {
            throw new IllegalStateException("async boom");
        }
    }

    public static class CapturingRabbitTemplate extends RabbitTemplate {
        @Override
        public void afterPropertiesSet() {
            // No broker connection is needed for this instrumentation fixture.
        }

        @Override
        public void send(String exchange, String routingKey, Message message) {
            // no broker needed; RabbitTemplate post-processor instrumentation is the subject.
        }
    }
}
