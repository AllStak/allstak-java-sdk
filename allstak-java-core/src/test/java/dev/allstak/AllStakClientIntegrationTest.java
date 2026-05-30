package dev.allstak;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.allstak.model.HttpRequestItem;
import dev.allstak.model.JobHandle;
import dev.allstak.model.UserContext;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.*;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.time.Duration;

/**
 * Integration test for the full AllStakClient lifecycle including buffering and flushing.
 */
class AllStakClientIntegrationTest {

    private static WireMockServer wireMock;
    private AllStakClient client;

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
        // Stub all endpoints to return 202
        wireMock.stubFor(post(urlPathMatching("/ingest/v1/.*"))
                .willReturn(aResponse().withStatus(202)
                        .withBody("{\"success\":true,\"data\":{\"id\":\"test\"}}")));

        AllStakConfig config = AllStakConfig.builder()
                .apiKey("ask_live_integration_test")
                .environment("test")
                .release("v0.0.1-test")
                .debug(true)
                .flushIntervalMs(500) // fast flush for tests
                .bufferSize(100)
                .serviceName("test-service")
                // These integration tests pre-date the privacy-by-default PII
                // behavior (Phase A.3) and assert email/ip on outgoing events. Opt in
                // here so the original assertions stand; the privacy-by-default
                // path is covered by PiiDefaultsTest.
                .sendDefaultPii(true)
                .build();

        // Inject a transport pointing at WireMock — bypasses the static ingest host
        HttpTransport transport = new HttpTransport(
                "http://localhost:" + wireMock.port(),
                config.getApiKey()
        );
        client = new AllStakClient(config, transport);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
        }
    }

    // =========================================================================
    // Error capture — immediate send
    // =========================================================================

    @Test
    void captureException_sendsImmediately() {
        client.captureException(new RuntimeException("Test error"));

        wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                .withRequestBody(containing("\"exceptionClass\":\"java.lang.RuntimeException\""))
                .withRequestBody(containing("\"message\":\"Test error\""))
                .withRequestBody(containing("\"environment\":\"test\""))
                .withRequestBody(containing("\"release\":\"v0.0.1-test\"")));
    }

    @Test
    void captureException_withMetadataAndUserContext() {
        client.setUser(UserContext.of("user-42", "user@test.com", "192.168.1.1"));
        client.captureException(new IllegalStateException("Bad state"),
                Map.of("component", "OrderProcessor", "orderId", "ORD-99"));

        wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                .withRequestBody(containing("\"exceptionClass\":\"java.lang.IllegalStateException\""))
                .withRequestBody(containing("\"id\":\"user-42\""))
                .withRequestBody(containing("\"email\":\"user@test.com\""))
                .withRequestBody(containing("\"component\":\"OrderProcessor\""))
                .withRequestBody(containing("\"orderId\":\"ORD-99\"")));
    }

    @Test
    void captureException_stackTraceExtracted() {
        try {
            throw new ArithmeticException("Division by zero");
        } catch (Exception e) {
            client.captureException(e);
        }

        wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                .withRequestBody(containing("\"stackTrace\":["))
                .withRequestBody(containing("AllStakClientIntegrationTest")));
    }

    @Test
    void captureException_sensitiveMetadataMasked() {
        client.captureException(new RuntimeException("err"),
                Map.of("password", "secret123", "token", "jwt-xxx", "orderId", "ORD-1"));

        wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                .withRequestBody(containing("\"password\":\"[MASKED]\""))
                .withRequestBody(containing("\"token\":\"[MASKED]\""))
                .withRequestBody(containing("\"orderId\":\"ORD-1\"")));
    }

    // =========================================================================
    // Log capture — buffered
    // =========================================================================

    @Test
    void captureLog_bufferedAndFlushed() {
        client.captureLog("info", "Test log message", Map.of("key", "val"));

        // Not sent immediately — buffered
        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/logs")));

        // Wait for flush timer
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                        .withRequestBody(containing("\"level\":\"info\""))
                        .withRequestBody(containing("\"message\":\"Test log message\""))));
    }

    @Test
    void captureLog_withServiceAndTraceId() {
        client.captureLog("error", "DB connection failed",
                "db-service", "trace-abc123", Map.of("host", "db.local"));

        client.flush();

        wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                .withRequestBody(containing("\"service\":\"db-service\""))
                .withRequestBody(containing("\"traceId\":\"trace-abc123\"")));
    }

    @Test
    void captureLog_invalidLevelDropped() {
        client.captureLog("invalid_level", "Should be dropped");
        client.flush();

        // No log sent because level is invalid
        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/logs")));
    }

    @Test
    void captureLog_sensitiveMetadataMasked() {
        client.captureLog("info", "test", Map.of("secret", "shh", "data", "ok"));
        client.flush();

        wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                .withRequestBody(containing("\"secret\":\"[MASKED]\""))
                .withRequestBody(containing("\"data\":\"ok\"")));
    }

    // =========================================================================
    // HTTP request monitoring — buffered in batches
    // =========================================================================

    @Test
    void captureHttpRequest_bufferedAndFlushedInBatch() {
        HttpRequestItem item = HttpRequestItem.builder()
                .traceId("t-001")
                .direction("inbound")
                .method("GET")
                .host("api.test.com")
                .path("/v1/users")
                .statusCode(200)
                .durationMs(42)
                .requestSize(0)
                .responseSize(1024)
                .timestamp("2026-04-01T10:00:00Z")
                .build();

        client.captureHttpRequest(item);

        // Wait for flush (500ms interval + network time)
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/http-requests"))
                        .withRequestBody(containing("\"requests\":["))
                        .withRequestBody(containing("\"direction\":\"inbound\""))));
    }

    @Test
    void captureHttpRequest_queryParamsStripped() {
        HttpRequestItem item = HttpRequestItem.builder()
                .traceId("t-002")
                .direction("outbound")
                .method("GET")
                .host("api.test.com")
                .path("/v1/search?token=secret&q=test")
                .statusCode(200)
                .durationMs(100)
                .requestSize(0)
                .responseSize(512)
                .timestamp("2026-04-01T10:00:00Z")
                .build();

        client.captureHttpRequest(item);
        client.flush();

        wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/http-requests"))
                .withRequestBody(containing("\"path\":\"/v1/search\""))
        );
    }

    // =========================================================================
    // Cron job monitoring — immediate send
    // =========================================================================

    @Test
    void cronJobMonitoring_successFlow() throws InterruptedException {
        JobHandle handle = client.startJob("daily-report");
        Thread.sleep(50);
        client.finishJob(handle, "success", "Processed 42 records");

        wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/heartbeat"))
                .withRequestBody(containing("\"slug\":\"daily-report\""))
                .withRequestBody(containing("\"status\":\"success\""))
                .withRequestBody(containing("\"message\":\"Processed 42 records\"")));
    }

    @Test
    void cronJobMonitoring_failedFlow() {
        JobHandle handle = client.startJob("payment-sync");
        client.finishJob(handle, "FAILED", "DB connection refused");

        wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/heartbeat"))
                .withRequestBody(containing("\"status\":\"failed\"")) // normalized to lowercase
                .withRequestBody(containing("\"slug\":\"payment-sync\"")));
    }

    // =========================================================================
    // Flush and Shutdown
    // =========================================================================

    @Test
    void flush_sendsBufferedItems() {
        client.captureLog("info", "log1");
        client.captureLog("warn", "log2");
        client.captureLog("error", "log3");

        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/logs")));

        client.flush();

        wireMock.verify(3, postRequestedFor(urlEqualTo("/ingest/v1/logs")));
    }

    @Test
    void shutdown_drainsBuffers() {
        client.captureLog("info", "pre-shutdown log");
        client.shutdown();

        // After shutdown, the log should have been flushed
        wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/logs")));
    }

    @Test
    void afterShutdown_captureIsNoOp() {
        client.shutdown();
        client.captureException(new RuntimeException("Should not be sent"));
        client.captureLog("info", "Should not be sent");

        wireMock.verify(0, postRequestedFor(urlPathMatching("/ingest/v1/.*")));
    }

    // =========================================================================
    // 401 disables SDK
    // =========================================================================

    @Test
    void authFailure_disablesAllOperations() {
        wireMock.stubFor(post(urlEqualTo("/ingest/v1/errors"))
                .willReturn(aResponse().withStatus(401)
                        .withBody("{\"success\":false,\"error\":{\"code\":\"INVALID_API_KEY\"}}")));

        client.captureException(new RuntimeException("trigger 401"));

        // Transport is now disabled
        assertThat(client.getTransport().isDisabled()).isTrue();

        // Subsequent operations should not send any HTTP requests
        wireMock.resetRequests();
        client.captureException(new RuntimeException("should be blocked"));
        client.captureLog("info", "should be blocked");
        client.flush();

        wireMock.verify(0, postRequestedFor(urlPathMatching("/ingest/v1/.*")));
    }
}
