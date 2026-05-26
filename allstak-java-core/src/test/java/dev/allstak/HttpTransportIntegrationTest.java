package dev.allstak;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import dev.allstak.model.*;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests that send real HTTP requests to a WireMock server,
 * validating the full transport → serialization → retry chain.
 */
class HttpTransportIntegrationTest {

    private static WireMockServer wireMock;
    private HttpTransport transport;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        transport = new HttpTransport("http://localhost:" + wireMock.port(), "allstak_live_test123");
    }

    // =========================================================================
    // Error Capture
    // =========================================================================

    @Test
    void sendError_success() {
        wireMock.stubFor(post(urlEqualTo("/ingest/v1/errors"))
                .willReturn(aResponse().withStatus(202)
                        .withBody("{\"success\":true,\"data\":{\"id\":\"test-uuid\"}}")));

        ErrorEvent event = new ErrorEvent("NullPointerException", "test msg",
                List.of("at Foo.bar(Foo.java:1)"), "error", "production", "v1.0.0",
                null, UserContext.ofId("usr-1"), Map.of("key", "val"));

        boolean result = transport.send("/ingest/v1/errors", event);

        assertThat(result).isTrue();
        wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                .withHeader("X-AllStak-Key", equalTo("allstak_live_test123"))
                .withHeader("Content-Type", equalTo("application/json")));
    }

    @Test
    void sendError_payloadHasCorrectFields() {
        wireMock.stubFor(post(urlEqualTo("/ingest/v1/errors"))
                .willReturn(aResponse().withStatus(202).withBody("{\"success\":true}")));

        ErrorEvent event = new ErrorEvent("RuntimeException", "Something broke",
                List.of("at com.test.Main.run(Main.java:10)"),
                "fatal", "staging", "v2.0.0", null,
                UserContext.of("u1", "u1@test.com", "10.0.0.1"),
                Map.of("component", "PaymentService"));

        transport.send("/ingest/v1/errors", event);

        wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                .withRequestBody(containing("\"exceptionClass\":\"RuntimeException\""))
                .withRequestBody(containing("\"message\":\"Something broke\""))
                .withRequestBody(containing("\"level\":\"fatal\""))
                .withRequestBody(containing("\"environment\":\"staging\""))
                .withRequestBody(containing("\"release\":\"v2.0.0\""))
                .withRequestBody(containing("\"component\":\"PaymentService\""))
                .withRequestBody(containing("\"email\":\"u1@test.com\"")));
    }

    // =========================================================================
    // Log Capture
    // =========================================================================

    @Test
    void sendLog_success() {
        wireMock.stubFor(post(urlEqualTo("/ingest/v1/logs"))
                .willReturn(aResponse().withStatus(202).withBody("{\"success\":true}")));

        LogEvent event = new LogEvent("warn", "Payment retry attempt 3",
                "payment-service", "trace-001", Map.of("orderId", "ORD-1"));

        boolean result = transport.send("/ingest/v1/logs", event);

        assertThat(result).isTrue();
        wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                .withRequestBody(containing("\"level\":\"warn\""))
                .withRequestBody(containing("\"service\":\"payment-service\""))
                .withRequestBody(containing("\"traceId\":\"trace-001\"")));
    }

    // =========================================================================
    // HTTP Request Batch
    // =========================================================================

    @Test
    void sendHttpBatch_success() {
        wireMock.stubFor(post(urlEqualTo("/ingest/v1/http-requests"))
                .willReturn(aResponse().withStatus(202)
                        .withBody("{\"ok\":true,\"accepted\":2}")));

        HttpRequestBatch batch = new HttpRequestBatch(List.of(
                HttpRequestItem.builder()
                        .traceId("t1").direction("inbound").method("GET")
                        .host("api.test.com").path("/v1/users")
                        .statusCode(200).durationMs(50)
                        .requestSize(0).responseSize(1024)
                        .timestamp("2026-03-31T12:00:00Z").build(),
                HttpRequestItem.builder()
                        .traceId("t2").direction("outbound").method("POST")
                        .host("payments.test.com").path("/v1/charges")
                        .statusCode(201).durationMs(320)
                        .requestSize(256).responseSize(512)
                        .timestamp("2026-03-31T12:00:01Z").build()
        ));

        boolean result = transport.send("/ingest/v1/http-requests", batch);

        assertThat(result).isTrue();
        wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/http-requests"))
                .withRequestBody(containing("\"requests\":["))
                .withRequestBody(containing("\"direction\":\"inbound\""))
                .withRequestBody(containing("\"direction\":\"outbound\"")));
    }

    // =========================================================================
    // Heartbeat
    // =========================================================================

    @Test
    void sendHeartbeat_success() {
        wireMock.stubFor(post(urlEqualTo("/ingest/v1/heartbeat"))
                .willReturn(aResponse().withStatus(202)
                        .withBody("{\"ok\":true,\"monitorId\":\"mon-uuid\"}")));

        HeartbeatEvent event = new HeartbeatEvent("daily-report", "success", 4320, "Processed 100 records");

        boolean result = transport.send("/ingest/v1/heartbeat", event);

        assertThat(result).isTrue();
        wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/heartbeat"))
                .withRequestBody(containing("\"slug\":\"daily-report\""))
                .withRequestBody(containing("\"status\":\"success\""))
                .withRequestBody(containing("\"durationMs\":4320")));
    }

    // =========================================================================
    // Error Handling & Retry Behavior
    // =========================================================================

    @Test
    void send_401_disablesTransport() {
        wireMock.stubFor(post(urlEqualTo("/ingest/v1/errors"))
                .willReturn(aResponse().withStatus(401)
                        .withBody("{\"success\":false,\"error\":{\"code\":\"INVALID_API_KEY\"}}")));

        boolean result = transport.send("/ingest/v1/errors",
                new ErrorEvent("E", "msg", null, "error", null, null, null, null, null));

        assertThat(result).isFalse();
        assertThat(transport.isDisabled()).isTrue();

        // Subsequent sends should be no-ops
        boolean result2 = transport.send("/ingest/v1/errors",
                new ErrorEvent("E", "msg2", null, "error", null, null, null, null, null));
        assertThat(result2).isFalse();

        // Only one actual HTTP request was made (the second was blocked)
        wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/errors")));
    }

    @Test
    void send_400_doesNotRetry() {
        wireMock.stubFor(post(urlEqualTo("/ingest/v1/logs"))
                .willReturn(aResponse().withStatus(400)
                        .withBody("{\"success\":false,\"error\":{\"code\":\"BAD_REQUEST\"}}")));

        boolean result = transport.send("/ingest/v1/logs",
                new LogEvent("info", "test", null, null, null));

        assertThat(result).isFalse();
        wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/logs")));
    }

    @Test
    void send_500_retriesAndEventuallySucceeds() {
        // First two attempts return 500, third returns 202
        wireMock.stubFor(post(urlEqualTo("/ingest/v1/logs"))
                .inScenario("retry")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("attempt-2"));

        wireMock.stubFor(post(urlEqualTo("/ingest/v1/logs"))
                .inScenario("retry")
                .whenScenarioStateIs("attempt-2")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("attempt-3"));

        wireMock.stubFor(post(urlEqualTo("/ingest/v1/logs"))
                .inScenario("retry")
                .whenScenarioStateIs("attempt-3")
                .willReturn(aResponse().withStatus(202).withBody("{\"success\":true}")));

        boolean result = transport.send("/ingest/v1/logs",
                new LogEvent("info", "test", null, null, null));

        assertThat(result).isTrue();
        wireMock.verify(3, postRequestedFor(urlEqualTo("/ingest/v1/logs")));
    }

    @Test
    void send_429WithRetryAfter_retriesAndSucceeds() {
        // First attempt: 429 with Retry-After: 1 (honored instead of backoff),
        // second attempt: 202.
        wireMock.stubFor(post(urlEqualTo("/ingest/v1/logs"))
                .inScenario("retry-after")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
                .willSetStateTo("attempt-2"));

        wireMock.stubFor(post(urlEqualTo("/ingest/v1/logs"))
                .inScenario("retry-after")
                .whenScenarioStateIs("attempt-2")
                .willReturn(aResponse().withStatus(202).withBody("{\"success\":true}")));

        long start = System.currentTimeMillis();
        boolean result = transport.send("/ingest/v1/logs",
                new LogEvent("info", "test", null, null, null));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result).isTrue();
        wireMock.verify(2, postRequestedFor(urlEqualTo("/ingest/v1/logs")));
        // Honored ~1s Retry-After rather than the ~1000-1500ms backoff for
        // attempt 1; mainly assert it waited roughly a second (not instant).
        assertThat(elapsed).isGreaterThanOrEqualTo(900L);
    }

    @Test
    void send_networkError_retriesUpToMaxAttempts() {
        // Use an unreachable port to simulate network error
        HttpTransport badTransport = new HttpTransport("http://localhost:1", "test-key");

        boolean result = badTransport.send("/ingest/v1/errors",
                new ErrorEvent("E", "msg", null, "error", null, null, null, null, null));

        assertThat(result).isFalse();
    }
}
