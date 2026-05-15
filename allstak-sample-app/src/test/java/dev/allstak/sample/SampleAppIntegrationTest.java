package dev.allstak.sample;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;

/**
 * Full Spring Boot integration test.
 * Boots the real app, hits real endpoints, and verifies SDK sends real payloads
 * to a WireMock server simulating the AllStak backend.
 *
 * <p>Because the production ingest host is hardcoded inside the SDK, this test
 * uses a {@link TestConfiguration} to override the {@link AllStakClient} bean
 * with one whose {@link HttpTransport} points at WireMock.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {dev.allstak.sample.SampleApplication.class, SampleAppIntegrationTest.WireMockTransportConfig.class}
)
@AutoConfigureMockMvc
class SampleAppIntegrationTest {

    private static WireMockServer wireMock;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        stubIngestAccepted();
    }

    private static void stubIngestAccepted() {
        wireMock.stubFor(WireMock.post(urlPathMatching("/ingest/v1/.*"))
                .willReturn(aResponse().withStatus(202)
                        .withBody("{\"success\":true,\"data\":{\"id\":\"test-uuid\"}}")));
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
        stubIngestAccepted();
    }

    @DynamicPropertySource
    static void wireMockProperties(DynamicPropertyRegistry registry) {
        registry.add("allstak.api-key", () -> "ask_live_integration_test_key");
        registry.add("allstak.environment", () -> "integration-test");
        registry.add("allstak.release", () -> "v1.0.0-test");
        registry.add("allstak.debug", () -> "true");
        registry.add("allstak.flush-interval-ms", () -> "500");
        registry.add("allstak.buffer-size", () -> "100");
        registry.add("allstak.service-name", () -> "sample-integration-test");
    }

    /**
     * Replaces the auto-configured {@link HttpTransport} bean with one pointing
     * at WireMock. The auto-config picks this up via {@code @ConditionalOnMissingBean}
     * and constructs the {@code AllStakClient} with our transport, so requests
     * never go to the static production ingest host.
     */
    @TestConfiguration
    static class WireMockTransportConfig {
        @Bean
        public HttpTransport allStakHttpTransport() {
            return new HttpTransport(
                    "http://localhost:" + wireMock.port(),
                    "ask_live_integration_test_key"
            );
        }
    }

    // =========================================================================
    // App Startup & Health
    // =========================================================================

    @Test
    void appStartsWithSdkInitialized() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/test/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.sdk_initialized").value("true"));
    }

    // =========================================================================
    // HTTP Request Monitoring via Servlet Filter
    // =========================================================================

    @Test
    void servletFilter_capturesInboundRequests() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/test/health"))
                .andExpect(status().isOk());

        // Wait for buffered HTTP request to be flushed
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/http-requests"))
                        .withRequestBody(containing("\"direction\":\"inbound\""))
                        .withRequestBody(containing("\"method\":\"GET\""))
                        .withRequestBody(containing("\"path\":\"/test/health\""))));
    }

    // =========================================================================
    // Exception Capture via Global Handler
    // =========================================================================

    @Test
    void globalExceptionHandler_capturesUnhandledExceptions() throws Exception {
        try {
            mockMvc.perform(MockMvcRequestBuilders.get("/test/throw-exception"));
        } catch (Exception ignored) {
            // Exception is re-thrown by the handler — expected
        }

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                        .withRequestBody(containing("\"exceptionClass\":\"java.lang.RuntimeException\""))
                        .withRequestBody(containing("\"message\":\"Intentional test exception from controller\""))
                        .withRequestBody(containing("\"environment\":\"integration-test\""))
                        .withRequestBody(containing("\"release\":\"v1.0.0-test\""))));
    }

    // =========================================================================
    // Manual Error Capture
    // =========================================================================

    @Test
    void manualErrorCapture_sendsToBackend() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/test/capture-error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("error captured"));

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                        .withRequestBody(containing("\"exceptionClass\":\"java.lang.IllegalStateException\""))
                        .withRequestBody(containing("\"source\":\"manual-test\""))
                        .withRequestBody(containing("\"priority\":\"high\""))));
    }

    // =========================================================================
    // Log Capture
    // =========================================================================

    @Test
    void logCapture_sendsBufferedLogs() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/test/capture-log")
                        .param("level", "warn")
                        .param("message", "Test warning log"))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                        .withRequestBody(containing("\"level\":\"warn\""))
                        .withRequestBody(containing("\"message\":\"Test warning log\""))));
    }

    @Test
    void logCapture_sensitiveMetadataMasked() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/test/capture-log-with-metadata"))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                        .withRequestBody(containing("\"password\":\"[MASKED]\""))
                        .withRequestBody(containing("\"token\":\"[MASKED]\""))
                        .withRequestBody(containing("\"orderId\":\"ORD-9821\""))));
    }

    // =========================================================================
    // Cron Job Monitoring
    // =========================================================================

    @Test
    void cronJobMonitoring_successScenario() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/test/cron-job")
                        .param("slug", "daily-report")
                        .param("status", "success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("cron job succeeded"));

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/heartbeat"))
                        .withRequestBody(containing("\"slug\":\"daily-report\""))
                        .withRequestBody(containing("\"status\":\"success\""))));
    }

    @Test
    void cronJobMonitoring_failedScenario() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/test/cron-job")
                        .param("slug", "payment-sync")
                        .param("status", "failed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("cron job failed"));

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/heartbeat"))
                        .withRequestBody(containing("\"slug\":\"payment-sync\""))
                        .withRequestBody(containing("\"status\":\"failed\""))));
    }

    // =========================================================================
    // Flush Endpoint
    // =========================================================================

    @Test
    void flushEndpoint_triggersImmediateFlush() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/test/capture-log")
                .param("level", "info")
                .param("message", "pre-flush log"));

        mockMvc.perform(MockMvcRequestBuilders.post("/test/flush"))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                        .withRequestBody(containing("\"message\":\"pre-flush log\""))));
    }
}
