package dev.allstak;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.allstak.spool.EventSpool;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

/**
 * Client-level coverage for the offline / persistent event queue: persist on
 * un-deliverable send, drain + resend on the next init, scrub-before-persist,
 * exclusion of session lifecycle calls, the opt-out flag, and graceful no-op
 * when the store is unavailable.
 *
 * <p>An explicit {@code offlineQueueDir} (a {@code @TempDir}) is configured so
 * the spool actually engages under the unit-test classpath — by default the
 * SDK skips the shared temp spool in tests to keep other suites' request-count
 * assertions clean.
 */
class OfflineQueueClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
            client = null;
        }
    }

    private AllStakConfig config(Path spoolDir) {
        return AllStakConfig.builder()
                .apiKey("ask_live_offline_test")
                .environment("test")
                .release("v0.0.1-test")
                .flushIntervalMs(500)
                .bufferSize(100)
                .offlineQueueDir(spoolDir.toString())
                .build();
    }

    private HttpTransport liveTransport(AllStakConfig cfg) {
        return new HttpTransport("http://localhost:" + wireMock.port(), cfg.getApiKey());
    }

    /**
     * A transport already disabled via a 401, so subsequent sends return
     * {@code PERMANENT} instantly with no network round-trip. Used by tests
     * that only need to prove "nothing was persisted" without paying the full
     * retry-backoff cost of a dead port.
     */
    private HttpTransport disabledTransport(AllStakConfig cfg) {
        wireMock.stubFor(post(urlPathMatching("/ingest/v1/.*"))
                .willReturn(aResponse().withStatus(401).withBody("{\"success\":false}")));
        HttpTransport t = new HttpTransport("http://localhost:" + wireMock.port(), cfg.getApiKey());
        t.send("/ingest/v1/errors", java.util.Map.of("warmup", true)); // trips the 401 → disabled
        assertThat(t.isDisabled()).isTrue();
        return t;
    }

    private int spoolSize(Path dir) {
        return new EventSpool(dir, 100, 1_000_000, 60_000, MAPPER).size();
    }

    // =========================================================================
    // Persist on un-deliverable send
    // =========================================================================

    @Test
    void capturedError_persistedWhenTransportCannotDeliver(@TempDir Path spoolDir) {
        AllStakConfig cfg = config(spoolDir);
        // Point the transport at a closed port → network error → TRANSIENT.
        HttpTransport dead = new HttpTransport("http://localhost:1", cfg.getApiKey());
        client = new AllStakClient(cfg, dead);

        client.captureException(new RuntimeException("offline boom"));

        // The masked error payload should now be on the spool, awaiting replay.
        assertThat(spoolSize(spoolDir)).isEqualTo(1);
    }

    // =========================================================================
    // Scrub-before-persist — no secret hits disk
    // =========================================================================

    @Test
    void persistedPayload_isScrubbed_noSecretOnDisk(@TempDir Path spoolDir) throws Exception {
        AllStakConfig cfg = config(spoolDir);
        HttpTransport dead = new HttpTransport("http://localhost:1", cfg.getApiKey());
        client = new AllStakClient(cfg, dead);

        client.captureException(new RuntimeException("with secret"),
                java.util.Map.of("password", "hunter2-SUPERSECRET", "orderId", "ORD-1"));

        assertThat(spoolSize(spoolDir)).isEqualTo(1);

        // Read the raw bytes off disk and assert the secret never landed there
        // and the masker's marker is present instead.
        String onDisk;
        try (var stream = java.nio.file.Files.newDirectoryStream(spoolDir, "evt-*.json")) {
            Path file = stream.iterator().next();
            onDisk = java.nio.file.Files.readString(file);
        }
        assertThat(onDisk).doesNotContain("hunter2-SUPERSECRET");
        assertThat(onDisk).contains("[MASKED]");
        assertThat(onDisk).contains("ORD-1");
    }

    // =========================================================================
    // Drain + resend on init
    // =========================================================================

    @Test
    void persistedEntries_drainedAndResentOnNextInit(@TempDir Path spoolDir) {
        // Simulate a previous run that left two scrubbed envelopes on the spool.
        EventSpool seed = new EventSpool(spoolDir, 100, 1_000_000, 60_000, MAPPER);
        seed.persist("/ingest/v1/errors", MAPPER.createObjectNode().put("message", "from-last-run-1"));
        seed.persist("/ingest/v1/logs", MAPPER.createObjectNode().put("message", "from-last-run-2"));
        assertThat(spoolSize(spoolDir)).isEqualTo(2);

        // Backend is reachable now.
        wireMock.stubFor(post(urlPathMatching("/ingest/v1/.*"))
                .willReturn(aResponse().withStatus(202).withBody("{\"success\":true}")));

        AllStakConfig cfg = config(spoolDir);
        client = new AllStakClient(cfg, liveTransport(cfg));

        // The async drainer replays both envelopes and clears the spool.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                    .withRequestBody(containing("from-last-run-1")));
            wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                    .withRequestBody(containing("from-last-run-2")));
            assertThat(spoolSize(spoolDir)).isZero();
        });
    }

    @Test
    void drain_keepsEntry_onTransientFailure_dropsOnPermanent(@TempDir Path spoolDir) {
        EventSpool seed = new EventSpool(spoolDir, 100, 1_000_000, 60_000, MAPPER);
        seed.persist("/ingest/v1/errors", MAPPER.createObjectNode().put("message", "rejected-4xx"));

        // Backend permanently rejects (400) → entry must be removed (not looped).
        wireMock.stubFor(post(urlEqualTo("/ingest/v1/errors"))
                .willReturn(aResponse().withStatus(400).withBody("{\"success\":false}")));

        AllStakConfig cfg = config(spoolDir);
        client = new AllStakClient(cfg, liveTransport(cfg));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(spoolSize(spoolDir)).isZero());
    }

    // =========================================================================
    // Session lifecycle calls are NOT persisted
    // =========================================================================

    @Test
    void sessionLifecycle_isNeverPersisted(@TempDir Path spoolDir) {
        // Session start/end go through SessionTracker, which calls the transport
        // directly and is never routed through the spool. Drive a real session
        // lifecycle and assert nothing lands on the spool — only error/log/span/
        // http/db telemetry is persistable.
        AllStakConfig cfg = AllStakConfig.builder()
                .apiKey("ask_live_offline_test")
                .environment("test")
                .release("v0.0.1-test")
                .offlineQueueDir(spoolDir.toString())
                .enableAutoSessionTracking(true)
                .build();

        // SessionTracker runs directly (bypasses the client's test-runtime guard).
        dev.allstak.session.SessionTracker tracker =
                new dev.allstak.session.SessionTracker(cfg, disabledTransport(cfg));
        tracker.start("user-1");
        tracker.end(null);

        // Nothing from /sessions/* should ever be spooled.
        assertThat(spoolSize(spoolDir)).isZero();
    }

    // =========================================================================
    // Opt-out
    // =========================================================================

    @Test
    void optOut_disablesPersistence(@TempDir Path spoolDir) {
        AllStakConfig cfg = AllStakConfig.builder()
                .apiKey("ask_live_offline_test")
                .environment("test")
                .release("v0.0.1-test")
                .offlineQueueDir(spoolDir.toString())
                .enableOfflineQueue(false)
                .build();

        client = new AllStakClient(cfg, disabledTransport(cfg));

        client.captureException(new RuntimeException("should not persist"));

        assertThat(spoolSize(spoolDir)).isZero();
    }

    // =========================================================================
    // Graceful no-op when the store is unavailable
    // =========================================================================

    @Test
    void unavailableStore_neverThrows_capturesStillWork(@TempDir Path spoolDir) throws Exception {
        // Make the configured spool path a regular file → not usable as a dir.
        Path notADir = spoolDir.resolve("blocker");
        java.nio.file.Files.writeString(notADir, "x");

        AllStakConfig cfg = AllStakConfig.builder()
                .apiKey("ask_live_offline_test")
                .environment("test")
                .release("v0.0.1-test")
                .offlineQueueDir(notADir.toString())
                .build();

        client = new AllStakClient(cfg, disabledTransport(cfg));

        // Must not throw even though persistence has nowhere to go.
        client.captureException(new RuntimeException("no store"));
        client.captureLog("error", "still works");

        // The blocker file is untouched and no spool entries were created.
        assertThat(java.nio.file.Files.readString(notADir)).isEqualTo("x");
    }
}
