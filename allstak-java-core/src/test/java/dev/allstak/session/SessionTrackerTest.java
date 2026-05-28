package dev.allstak.session;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.allstak.AllStakConfig;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.*;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SessionTrackerTest {

    private static WireMockServer wireMock;
    private HttpTransport transport;
    private AllStakConfig config;

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
        wireMock.stubFor(post(urlPathMatching("/ingest/v1/sessions/.*"))
                .willReturn(aResponse().withStatus(202)
                        .withBody("{\"success\":true,\"data\":{\"id\":\"x\",\"sessionId\":\"y\"}}")));

        config = AllStakConfig.builder()
                .apiKey("ask_live_test")
                .environment("test")
                .release("v0.0.1-test")
                .debug(true)
                .build();

        transport = new HttpTransport("http://localhost:" + wireMock.port(), config.getApiKey());
    }

    @Test
    void start_postsSessionStart_withConfiguredAttributes() {
        SessionTracker tracker = new SessionTracker(config, transport);
        tracker.start();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/sessions/start"))
                        .withRequestBody(matchingJsonPath("$.sessionId"))
                        .withRequestBody(matchingJsonPath("$[?(@.release == 'v0.0.1-test')]"))
                        .withRequestBody(matchingJsonPath("$[?(@.environment == 'test')]"))
                        .withRequestBody(matchingJsonPath("$[?(@.sdkName == 'allstak-java')]"))
                        .withRequestBody(matchingJsonPath("$[?(@.platform == 'jvm')]"))));
    }

    @Test
    void start_isIdempotent() {
        SessionTracker tracker = new SessionTracker(config, transport);
        Session first = tracker.start();
        Session second = tracker.start();
        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void end_postsCorrectStatus_okWhenNoErrors() {
        SessionTracker tracker = new SessionTracker(config, transport);
        tracker.start();
        tracker.end(null);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/sessions/end"))
                        .withRequestBody(matchingJsonPath("$[?(@.status == 'ok')]"))));
    }

    @Test
    void recordError_thenEnd_postsErroredStatus() {
        SessionTracker tracker = new SessionTracker(config, transport);
        tracker.start();
        tracker.recordError();
        tracker.recordError();
        tracker.end(null);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/sessions/end"))
                        .withRequestBody(matchingJsonPath("$[?(@.status == 'errored')]"))));
    }

    @Test
    void recordCrash_thenEnd_postsCrashedStatus() {
        SessionTracker tracker = new SessionTracker(config, transport);
        tracker.start();
        tracker.recordError();
        tracker.recordCrash();
        tracker.end(null);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/sessions/end"))
                        .withRequestBody(matchingJsonPath("$[?(@.status == 'crashed')]"))));
    }

    @Test
    void end_isIdempotent_secondCallNoOp() {
        SessionTracker tracker = new SessionTracker(config, transport);
        tracker.start();
        tracker.end(null);
        tracker.end(null);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/sessions/end"))));
    }

    @Test
    void noRelease_stillSends_fallingBackToSdkVersion() {
        // Release-health sessions are NEVER sampled: when no release is
        // resolved the start envelope falls back to the SDK version so the
        // session is still attributable rather than dropped.
        AllStakConfig noRelease = AllStakConfig.builder()
                .apiKey("ask_live_test")
                .environment("test")
                .autoDetectRelease(false)   // suppress git-describe and SDK_VERSION release resolution
                .build();
        SessionTracker tracker = new SessionTracker(noRelease, transport);
        Session s = tracker.start();
        tracker.recordError();
        tracker.end(null);

        assertThat(s.getStatus()).isEqualTo(SessionStatus.ERRORED);
        assertThat(s.getErrorCount()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/sessions/start"))
                        .withRequestBody(matchingJsonPath(
                                "$[?(@.release == '" + AllStakConfig.SDK_VERSION + "')]"))));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/sessions/end")));
    }

    @Test
    void transportDisabled_skipsNetwork_butInMemoryStillWorks() {
        // A transport disabled after a 401 keeps the in-memory tracker alive
        // for status transitions but performs no further network I/O.
        wireMock.resetAll();
        wireMock.stubFor(post(urlPathMatching("/ingest/v1/.*"))
                .willReturn(aResponse().withStatus(401)));
        // Trip the disabled flag with one send, then assert no session traffic.
        transport.send("/ingest/v1/errors", java.util.Map.of("k", "v"));
        assertThat(transport.isDisabled()).isTrue();
        wireMock.resetAll();

        SessionTracker tracker = new SessionTracker(config, transport);
        Session s = tracker.start();
        tracker.recordError();
        tracker.end(null);

        assertThat(s.getStatus()).isEqualTo(SessionStatus.ERRORED);
        assertThat(s.getErrorCount()).isEqualTo(1);
        wireMock.verify(0, postRequestedFor(urlPathMatching("/ingest/v1/sessions/.*")));
    }

    @Test
    void start_attachesUserId_whenProvided() {
        SessionTracker tracker = new SessionTracker(config, transport);
        tracker.start("user-42");

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/sessions/start"))
                        .withRequestBody(matchingJsonPath("$[?(@.userId == 'user-42')]"))));
    }

    @Test
    void currentSessionId_isStableAcrossCaptures_thenNullAfterEnd() {
        SessionTracker tracker = new SessionTracker(config, transport);
        Session s = tracker.start();

        assertThat(tracker.currentSessionId()).isEqualTo(s.getId());
        // stable across repeated reads (mirrors per-error event stamping)
        assertThat(tracker.currentSessionId()).isEqualTo(s.getId());

        tracker.end(null);
        assertThat(tracker.currentSessionId()).isNull();
    }

    @Test
    void end_carriesDurationMs() {
        SessionTracker tracker = new SessionTracker(config, transport);
        tracker.start();
        tracker.end(null);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/sessions/end"))
                        .withRequestBody(matchingJsonPath("$.durationMs"))
                        .withRequestBody(matchingJsonPath("$.sessionId"))));
    }

    @Test
    void explicitStatus_overridesAccumulatedStatus() {
        SessionTracker tracker = new SessionTracker(config, transport);
        tracker.start();
        tracker.recordError();
        tracker.end(SessionStatus.ABNORMAL);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/sessions/end"))
                        .withRequestBody(matchingJsonPath("$[?(@.status == 'abnormal')]"))));
    }
}
