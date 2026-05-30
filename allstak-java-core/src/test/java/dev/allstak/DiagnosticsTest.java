package dev.allstak;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.allstak.transport.HttpTransport;
import dev.allstak.tracing.Transaction;
import org.junit.jupiter.api.*;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticsTest {

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
        wireMock.stubFor(post(urlPathMatching("/ingest/v1/.*"))
                .willReturn(aResponse().withStatus(202).withBody("{\"success\":true}")));
        AllStak.reset();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
            client = null;
        }
        AllStak.reset();
    }

    @Test
    void facadeDiagnosticsBeforeInitReturnsDisabledSnapshot() {
        AllStakDiagnostics diagnostics = AllStak.getDiagnostics();

        assertThat(diagnostics.isDisabled()).isTrue();
        assertThat(diagnostics.getEventsCaptured()).isZero();
        assertThat(diagnostics.getQueueSize()).isZero();
    }

    @Test
    void clientDiagnosticsAggregatesCountersWithoutPayloadData() {
        AllStakConfig cfg = AllStakConfig.builder()
                .apiKey("ask_live_diag_test")
                .environment("test")
                .release("v0.0.1-test")
                .enableOfflineQueue(false)
                .enableAutoSessionTracking(false)
                .installUncaughtExceptionHandler(false)
                .flushIntervalMs(60_000)
                .bufferSize(100)
                .build();
        client = new AllStakClient(cfg, new HttpTransport("http://localhost:" + wireMock.port(), cfg.getApiKey()));

        client.addBreadcrumb("custom", "ready");
        client.captureLog("info", "user test@example.com", Map.of("password", "secret"));
        Transaction tx = Transaction.start(client, "diagnostics", "test");

        AllStakDiagnostics diagnostics = client.getDiagnostics();

        assertThat(diagnostics.isDisabled()).isFalse();
        assertThat(diagnostics.getEventsCaptured()).isGreaterThanOrEqualTo(1);
        assertThat(diagnostics.getQueueSize()).isGreaterThanOrEqualTo(1);
        assertThat(diagnostics.getBreadcrumbCount()).isEqualTo(1);
        assertThat(diagnostics.getActiveTraceCount()).isEqualTo(1);
        assertThat(diagnostics.getActiveSpanCount()).isEqualTo(1);
        assertThat(diagnostics.getSanitizerRedactionCount()).isGreaterThanOrEqualTo(1);

        tx.finish();
    }
}
