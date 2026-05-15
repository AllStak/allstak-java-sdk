package dev.allstak.spring;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.model.RequestContext;
import dev.allstak.transport.HttpTransport;
import feign.Client;
import feign.Request;
import feign.Response;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.validation.metadata.ConstraintDescriptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.*;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.retry.annotation.Retryable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class OptionalBackendInstrumentationTest {
    private static WireMockServer ingest;
    private AllStakClient client;

    @BeforeAll
    static void startServer() {
        ingest = new WireMockServer(wireMockConfig().dynamicPort());
        ingest.start();
        ingest.stubFor(post(urlPathMatching("/ingest/v1/.*")).willReturn(aResponse().withStatus(202)));
    }

    @AfterAll
    static void stopServer() {
        if (ingest != null) ingest.stop();
    }

    @BeforeEach
    void setup() {
        ingest.resetRequests();
        client = new AllStakClient(AllStakConfig.builder()
                .apiKey("ask_live_test_key")
                .host("http://localhost:" + ingest.port())
                .environment("optional-test")
                .release("v1.0.0-optional")
                .serviceName("optional-test")
                .flushIntervalMs(100)
                .bufferSize(100)
                .build(), new HttpTransport("http://localhost:" + ingest.port(), "ask_live_test_key"));
    }

    @AfterEach
    void teardown() {
        AllStakClient.clearRequestContext();
        client.shutdown();
    }

    @Test
    void kafkaListener_successAndFailureCaptureConsumerSpansAndExactMetadata() {
        KafkaProbe probe = (KafkaProbe) new AllStakKafkaListenerPostProcessor(client)
                .postProcessAfterInitialization(new KafkaProbe(), "kafkaProbe");

        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.events", 2, 42L, "customer-123", "{}");
        record.headers().add(new RecordHeader(AllStakKafkaSupport.HEADER_TRACE_ID, "trace-kafka-success".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(AllStakKafkaSupport.HEADER_REQUEST_ID, "req-kafka-success".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("kafka_deliveryAttempt", "3".getBytes(StandardCharsets.UTF_8)));

        probe.handle(record);

        ConsumerRecord<String, String> failingRecord = new ConsumerRecord<>("orders.events.DLT", 1, 43L, "customer-456", "{}");
        failingRecord.headers().add(new RecordHeader(AllStakKafkaSupport.HEADER_TRACE_ID, "trace-kafka-fail".getBytes(StandardCharsets.UTF_8)));
        failingRecord.headers().add(new RecordHeader(AllStakKafkaSupport.HEADER_REQUEST_ID, "req-kafka-fail".getBytes(StandardCharsets.UTF_8)));

        Throwable thrown = null;
        try {
            probe.fail(failingRecord);
        } catch (Throwable t) {
            thrown = t;
        }
        assertThat(thrown).isInstanceOf(IllegalStateException.class);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"messaging.system\":\"kafka\""))
                    .withRequestBody(containing("\"messaging.kafka.partition\":\"2\""))
                    .withRequestBody(containing("\"messaging.kafka.offset\":\"42\""))
                    .withRequestBody(containing("\"messaging.kafka.delivery_attempt\":\"3\"")));
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                    .withRequestBody(containing("spring.kafka.listener"))
                    .withRequestBody(containing("req-kafka-fail"))
                    .withRequestBody(containing("kafka listener boom")));
        });
    }

    @Test
    void kafkaTemplate_sendInjectsHeadersAndCreatesProducerSpan() {
        CapturingKafkaTemplate template = (CapturingKafkaTemplate) new AllStakKafkaTemplatePostProcessor(client)
                .postProcessAfterInitialization(new CapturingKafkaTemplate(), "kafkaTemplate");
        ProducerRecord<String, String> record = new ProducerRecord<>("orders.commands", "customer-789", "{}");

        template.send(record);

        assertThat(record.headers().lastHeader(AllStakKafkaSupport.HEADER_TRACE_ID)).isNotNull();
        assertThat(record.headers().lastHeader(AllStakKafkaSupport.HEADER_REQUEST_ID)).isNotNull();
        assertThat(record.headers().lastHeader(AllStakKafkaSupport.HEADER_TRACEPARENT)).isNotNull();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                        .withRequestBody(containing("\"operation\":\"messaging.producer\""))
                        .withRequestBody(containing("\"messaging.system\":\"kafka\""))
                        .withRequestBody(containing("\"messaging.destination.name\":\"orders.commands\""))));
    }

    @Test
    void kafkaTemplate_commonSendOverloadsCaptureProducerMetadataAndMessageHeaders() {
        CapturingKafkaTemplate template = (CapturingKafkaTemplate) new AllStakKafkaTemplatePostProcessor(client)
                .postProcessAfterInitialization(new CapturingKafkaTemplate(), "kafkaTemplate");
        AllStakClient.setRequestContext(RequestContext.of("POST", "/orders", "app", (String) null, "trace-kafka-overloads", "req-kafka-overloads"));

        template.send("orders.value", "{}");
        template.send("orders.keyed", "customer-321", "{}");
        template.send("orders.partitioned", 3, "customer-654", "{}");
        Message<String> message = MessageBuilder.withPayload("{}")
                .setHeader(org.springframework.kafka.support.KafkaHeaders.TOPIC, "orders.message")
                .build();
        template.send(message);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"messaging.destination.name\":\"orders.value\"")));
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"messaging.destination.name\":\"orders.keyed\"")));
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"messaging.destination.name\":\"orders.partitioned\""))
                    .withRequestBody(containing("\"messaging.kafka.partition\":\"3\"")));
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"messaging.destination.name\":\"orders.message\"")));
        });
    }

    @Test
    void springCache_wrapsOperationsAndHashesKeys() {
        ConcurrentMapCacheManager manager = new ConcurrentMapCacheManager("users");
        var wrapped = (org.springframework.cache.CacheManager) new AllStakCacheManagerPostProcessor(client)
                .postProcessAfterInitialization(manager, "cacheManager");

        var cache = wrapped.getCache("users");
        assertThat(cache).isNotNull();
        cache.put("customer-secret-token", "ok");
        cache.get("customer-secret-token");

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                ingest.verify(2, postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                        .withRequestBody(containing("\"span.kind\":\"cache.client\""))
                        .withRequestBody(containing("\"cache.name\":\"users\""))));
        assertThat(ingest.findAll(postRequestedFor(urlEqualTo("/ingest/v1/spans"))).toString())
                .doesNotContain("customer-secret-token");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void redisTemplate_wrapsCommonHelperApisAndHashesKeys() {
        var subclass = withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS);
        RedisTemplate<String, String> template = mock(RedisTemplate.class, subclass);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class, subclass);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class, subclass);
        ListOperations<String, String> listOps = mock(ListOperations.class, subclass);
        SetOperations<String, String> setOps = mock(SetOperations.class, subclass);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class, subclass);
        when(template.opsForValue()).thenReturn(valueOps);
        when(template.opsForHash()).thenReturn((HashOperations) hashOps);
        when(template.opsForList()).thenReturn(listOps);
        when(template.opsForSet()).thenReturn(setOps);
        when(template.opsForZSet()).thenReturn(zSetOps);
        when(valueOps.get("customer-secret-token")).thenReturn("ok");
        when(hashOps.get("customer-secret-token", "field")).thenReturn("ok");
        when(listOps.leftPop("customer-secret-token")).thenReturn("ok");
        when(setOps.isMember("customer-secret-token", "member")).thenReturn(true);
        when(zSetOps.score("customer-secret-token", "member")).thenReturn(1.0);

        RedisTemplate<String, String> wrapped = (RedisTemplate<String, String>) new AllStakRedisTemplatePostProcessor(client)
                .postProcessAfterInitialization(template, "redisTemplate");

        wrapped.opsForValue().get("customer-secret-token");
        wrapped.opsForHash().get("customer-secret-token", "field");
        wrapped.opsForList().leftPop("customer-secret-token");
        wrapped.opsForSet().isMember("customer-secret-token", "member");
        wrapped.opsForZSet().score("customer-secret-token", "member");

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"cache.operation\":\"redis.value.get\"")));
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"cache.operation\":\"redis.hash.get\"")));
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"cache.operation\":\"redis.list.leftPop\"")));
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"cache.operation\":\"redis.set.isMember\"")));
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"cache.operation\":\"redis.zset.score\"")));
        });
        assertThat(ingest.findAll(postRequestedFor(urlEqualTo("/ingest/v1/spans"))).toString())
                .doesNotContain("customer-secret-token");
    }

    @Test
    void feignClient_propagatesHeadersAndCapturesOutboundRequest() throws Exception {
        Request.Options options = new Request.Options();
        Client delegate = (request, ignored) -> Response.builder()
                .status(200)
                .reason("OK")
                .request(request)
                .headers(Map.of())
                .build();
        Client wrapped = (Client) new AllStakFeignClientPostProcessor(client)
                .postProcessAfterInitialization(delegate, "feignClient");
        AllStakClient.setRequestContext(RequestContext.of("GET", "/frontend", "app", (String) null, "trace-feign", "req-feign"));
        feign.RequestTemplate template = new feign.RequestTemplate();
        new AllStakFeignRequestInterceptor().apply(template);
        Request request = Request.create(Request.HttpMethod.GET, "http://example.test/api/orders",
                template.headers(), null, StandardCharsets.UTF_8);

        wrapped.execute(request, options);

        assertThat(template.headers()).containsKeys(
                AllStakFeignRequestInterceptor.HEADER_TRACE_ID,
                AllStakFeignRequestInterceptor.HEADER_REQUEST_ID,
                AllStakFeignRequestInterceptor.HEADER_TRACEPARENT);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/http-requests"))
                        .withRequestBody(containing("\"direction\":\"outbound\""))
                        .withRequestBody(containing("\"method\":\"GET\""))
                        .withRequestBody(containing("\"path\":\"/api/orders\""))
                        .withRequestBody(containing("\"traceId\":\"trace-feign\""))));
    }

    @Test
    void validationExceptionsRecordFieldNamesWithoutRejectedValues() throws Exception {
        AllStakExceptionHandler handler = new AllStakExceptionHandler(client);
        BindException bind = new BindException(new Object(), "payload");
        bind.addError(new FieldError("payload", "password", "super-secret", false, null, null, "must not be blank"));

        try {
            handler.handleException(bind);
        } catch (BindException expected) {
            // original exception must continue to Spring's normal handling.
        }

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                        .withRequestBody(containing("\"error.category\":\"validation\""))
                        .withRequestBody(containing("\"validation.fields\":\"password\""))));
        assertThat(ingest.findAll(postRequestedFor(urlEqualTo("/ingest/v1/errors"))).toString())
                .doesNotContain("super-secret");
    }

    @Test
    void constraintViolationMetadataRecordsFieldNamesOnly() throws Exception {
        AllStakExceptionHandler handler = new AllStakExceptionHandler(client);
        ConstraintViolationException ex = new ConstraintViolationException("invalid", java.util.Set.of(new MinimalViolation("booking.date")));

        try {
            handler.handleException(ex);
        } catch (ConstraintViolationException expected) {
            // original exception must continue to Spring's normal handling.
        }

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                        .withRequestBody(containing("\"validation.source\":\"constraint_violation\""))
                        .withRequestBody(containing("\"validation.fields\":\"booking.date\""))));
    }

    @Test
    void securityAuthenticationFailureRecordsAuthSemanticsWithoutCredentials() {
        AllStakSecurityEventListener listener = new AllStakSecurityEventListener(client);
        TestingAuthenticationToken authentication = new TestingAuthenticationToken("customer@example.test", "raw-password");

        listener.onApplicationEvent(new AuthenticationFailureBadCredentialsEvent(
                authentication,
                new BadCredentialsException("Bad credentials")));

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                        .withRequestBody(containing("\"error.category\":\"auth\""))
                        .withRequestBody(containing("\"auth.failure_type\":\"BadCredentialsException\""))
                        .withRequestBody(containing("\"auth.principal.present\":true"))));
        assertThat(ingest.findAll(postRequestedFor(urlEqualTo("/ingest/v1/errors"))).toString())
                .doesNotContain("raw-password")
                .doesNotContain("customer@example.test");
    }

    @Test
    void optionalInstrumentationIsFailOpenWhenAllStakIsUnreachable() {
        AllStakClient downClient = new AllStakClient(AllStakConfig.builder()
                .apiKey("ask_live_test_key")
                .host("http://127.0.0.1:9")
                .environment("optional-test")
                .release("v1.0.0-optional")
                .serviceName("optional-test")
                .flushIntervalMs(100)
                .bufferSize(10)
                .build());
        long startNs = System.nanoTime();
        try {
            KafkaProbe probe = (KafkaProbe) new AllStakKafkaListenerPostProcessor(downClient)
                    .postProcessAfterInitialization(new KafkaProbe(), "kafkaProbe");
            probe.handle(new ConsumerRecord<>("orders.events", 0, 1L, "key", "{}"));

            var cache = ((org.springframework.cache.CacheManager) new AllStakCacheManagerPostProcessor(downClient)
                    .postProcessAfterInitialization(new ConcurrentMapCacheManager("users"), "cacheManager")).getCache("users");
            cache.put("customer-secret-token", "ok");

            new AllStakSecurityEventListener(downClient).onApplicationEvent(new AuthenticationFailureBadCredentialsEvent(
                    new TestingAuthenticationToken("customer@example.test", "raw-password"),
                    new BadCredentialsException("Bad credentials")));
        } finally {
            downClient.shutdown();
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        org.assertj.core.api.Assertions.assertThat(durationMs).isLessThan(500);
    }

    @Test
    void springRetryCapturesAttemptMetadataWithoutChangingCustomerBehavior() {
        RetryProbe probe = (RetryProbe) new AllStakRetryPostProcessor(client)
                .postProcessAfterInitialization(new RetryProbe(), "retryProbe");

        assertThat(probe.succeeds()).isEqualTo("ok");
        Throwable thrown = null;
        try {
            probe.fails();
        } catch (Throwable t) {
            thrown = t;
        }

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"retry.framework\":\"spring-retry\""))
                    .withRequestBody(containing("\"retry.max_attempts\":\"3\"")));
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"retry.final\":\"true\""))
                    .withRequestBody(containing("\"error.type\":\"java.lang.IllegalStateException\"")));
        });
    }

    public static class KafkaProbe {
        @KafkaListener(topics = "orders.events", groupId = "orders-service")
        public void handle(ConsumerRecord<String, String> record) {}

        @KafkaListener(topics = "orders.events", groupId = "orders-service")
        public void fail(ConsumerRecord<String, String> record) {
            throw new IllegalStateException("kafka listener boom");
        }
    }

    public static class CapturingKafkaTemplate extends KafkaTemplate<String, String> {
        Message<?> lastMessage;

        CapturingKafkaTemplate() {
            super(mock(org.springframework.kafka.core.ProducerFactory.class));
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(ProducerRecord<String, String> record) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(String topic, String data) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(String topic, String key, String data) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(String topic, Integer partition, String key, String data) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(Message<?> message) {
            this.lastMessage = message;
            return CompletableFuture.completedFuture(null);
        }
    }

    public static class RetryProbe {
        @Retryable(maxAttempts = 3)
        public String succeeds() {
            return "ok";
        }

        @Retryable(maxAttempts = 3)
        public String fails() {
            throw new IllegalStateException("retry failed");
        }
    }

    private record MinimalViolation(String path) implements jakarta.validation.ConstraintViolation<Object> {
        @Override public String getMessage() { return "invalid"; }
        @Override public String getMessageTemplate() { return "invalid"; }
        @Override public Object getRootBean() { return new Object(); }
        @Override public Class<Object> getRootBeanClass() { return Object.class; }
        @Override public Object getLeafBean() { return null; }
        @Override public Object[] getExecutableParameters() { return new Object[0]; }
        @Override public Object getExecutableReturnValue() { return null; }
        @Override public Path getPropertyPath() { return new Path() {
            @Override public Iterator<Node> iterator() {
                return java.util.List.<Path.Node>of(new Path.Node() {
            @Override public String getName() { return path; }
            @Override public boolean isInIterable() { return false; }
            @Override public Integer getIndex() { return null; }
            @Override public Object getKey() { return null; }
            @Override public <T extends Path.Node> T as(Class<T> nodeType) { return nodeType.cast(this); }
            @Override public ElementKind getKind() { return ElementKind.PROPERTY; }
                }).iterator();
            }
            @Override public String toString() { return path; }
        }; }
        @Override public Object getInvalidValue() { return "secret-value"; }
        @Override public ConstraintDescriptor<?> getConstraintDescriptor() { return null; }
        @Override public <U> U unwrap(Class<U> type) { return null; }
    }
}
