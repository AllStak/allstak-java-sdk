package dev.allstak.session;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Client-level coverage for the release-health session lifecycle wiring in
 * {@link AllStakClient}.
 *
 * <p>Under a unit-test runtime the SDK deliberately skips opening a session
 * (mirrors the release-registration guard) so the suite never hits
 * {@code /ingest/v1/sessions/start}. These tests therefore assert the two
 * suppression paths — the {@code enableAutoSessionTracking=false} opt-out and
 * the implicit test-runtime guard — both of which must produce zero session
 * traffic. The on-the-wire start/end payload shapes and the OK→ERRORED→CRASHED
 * status transitions are covered directly in {@link SessionTrackerTest}.
 */
class SessionTrackingClientTest {

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
                .willReturn(aResponse().withStatus(202)
                        .withBody("{\"success\":true,\"data\":{\"id\":\"test\"}}")));
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.shutdown();
    }

    private AllStakClient newClient(boolean enableAutoSessionTracking) {
        AllStakConfig config = AllStakConfig.builder()
                .apiKey("ask_live_session_test")
                .environment("test")
                .release("v0.0.1-test")
                .enableAutoSessionTracking(enableAutoSessionTracking)
                .build();
        HttpTransport transport = new HttpTransport("http://localhost:" + wireMock.port(), config.getApiKey());
        return new AllStakClient(config, transport);
    }

    @Test
    void optOut_disablesSessionTracking_noStartNoEnd() {
        client = newClient(false);
        client.captureException(new RuntimeException("boom"));
        client.shutdown();
        client = null; // already shut down

        wireMock.verify(0, postRequestedFor(urlPathMatching("/ingest/v1/sessions/.*")));
    }

    @Test
    void underTestRuntime_sessionTrackingIsSkipped_evenWhenEnabled() {
        // enableAutoSessionTracking defaults true, but the SDK skips opening a
        // session under a unit-test classpath. Surefire/JUnit is on the
        // classpath here, so no session traffic should be emitted.
        client = newClient(true);
        client.captureException(new RuntimeException("boom"));
        client.shutdown();
        client = null;

        wireMock.verify(0, postRequestedFor(urlPathMatching("/ingest/v1/sessions/.*")));
    }

    @Test
    void captureException_stillSendsError_whenSessionTrackingDisabled() {
        // Disabling session tracking must not regress error delivery.
        client = newClient(false);
        client.captureException(new RuntimeException("boom"));

        wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/errors")));
    }
}
