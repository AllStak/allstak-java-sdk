package dev.allstak;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.allstak.internal.UncaughtExceptionCapture;
import dev.allstak.model.ErrorEvent;
import dev.allstak.model.LogEvent;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.*;

import java.util.function.DoubleSupplier;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the beforeSend hook (modify / drop / fail-open), error+message
 * sampleRate, and the tracesSampleRate-driven traceparent sampled flag.
 */
class BeforeSendSamplingTest {

    private static WireMockServer wireMock;

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
                        .withBody("{\"success\":true,\"data\":{\"id\":\"x\"}}")));
    }

    private AllStakClient client(AllStakConfig config, DoubleSupplier sampler) {
        HttpTransport transport = new HttpTransport("http://localhost:" + wireMock.port(), config.getApiKey());
        return new AllStakClient(config, transport, sampler);
    }

    private AllStakConfig.Builder baseConfig() {
        // Opt out of the global handler so these tests don't fight over the JVM default.
        return AllStakConfig.builder()
                .apiKey("k")
                .environment("test")
                .flushIntervalMs(200)
                .installUncaughtExceptionHandler(false);
    }

    // ── beforeSend ────────────────────────────────────────────────────────

    @Test
    void beforeSendModifiesErrorEvent() {
        AllStakConfig config = baseConfig()
                .beforeSend(event -> {
                    if (event instanceof ErrorEvent e) {
                        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>(
                                e.getMetadata() == null ? java.util.Map.of() : e.getMetadata());
                        meta.put("injected", "by-before-send");
                        return new ErrorEvent(e.getExceptionClass(), e.getMessage(), e.getStackTrace(),
                                e.getLevel(), e.getEnvironment(), e.getRelease(), e.getSessionId(),
                                e.getUser(), meta, e.getTraceId(), e.getRequestContext(),
                                e.getBreadcrumbs(), e.getPlatform(), e.getSdkName(), e.getSdkVersion(),
                                e.getDist(), e.getFrames());
                    }
                    return event;
                })
                .build();
        AllStakClient client = client(config, () -> 0.0);
        client.captureException(new RuntimeException("boom"));

        wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                .withRequestBody(containing("\"injected\":\"by-before-send\"")));
        client.shutdown();
    }

    @Test
    void beforeSendDropsWhenReturningNull() {
        AllStakConfig config = baseConfig()
                .beforeSend(event -> null) // drop everything
                .build();
        AllStakClient client = client(config, () -> 0.0);
        client.captureException(new RuntimeException("boom"));
        client.captureLog("error", "should drop");
        client.flush();

        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/errors")));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/logs")));
        client.shutdown();
    }

    @Test
    void beforeSendThrowingIsFailOpen() {
        AllStakConfig config = baseConfig()
                .beforeSend(event -> { throw new IllegalStateException("hook bug"); })
                .build();
        AllStakClient client = client(config, () -> 0.0);
        client.captureException(new RuntimeException("boom"));

        // Original event still sent despite the throwing hook.
        wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                .withRequestBody(containing("\"message\":\"boom\"")));
        client.shutdown();
    }

    @Test
    void beforeSendRunsForMessageCapture() {
        AllStakConfig config = baseConfig()
                .beforeSend(event -> {
                    if (event instanceof LogEvent e && "drop-me".equals(e.getMessage())) {
                        return null;
                    }
                    return event;
                })
                .build();
        AllStakClient client = client(config, () -> 0.0);
        client.captureLog("info", "drop-me");
        client.captureLog("info", "keep-me");
        client.flush();

        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                .withRequestBody(containing("drop-me")));
        wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                .withRequestBody(containing("keep-me")));
        client.shutdown();
    }

    // ── sampleRate ──────────────────────────────────────────────────────────

    @Test
    void sampleRateZeroDropsEverything() {
        AllStakConfig config = baseConfig().sampleRate(0.0).build();
        AllStakClient client = client(config, () -> 0.5);
        client.captureException(new RuntimeException("boom"));
        client.captureLog("info", "msg");
        client.flush();

        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/errors")));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/logs")));
        client.shutdown();
    }

    @Test
    void sampleRateOneKeepsEverything() {
        AllStakConfig config = baseConfig().sampleRate(1.0).build();
        // Sampler returns 0.999 — irrelevant because rate>=1.0 short-circuits.
        AllStakClient client = client(config, () -> 0.999);
        client.captureException(new RuntimeException("boom"));
        client.captureLog("info", "msg");
        client.flush();

        wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/errors")));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/logs")));
        client.shutdown();
    }

    @Test
    void sampleRatePartialUsesRng() {
        AllStakConfig config = baseConfig().sampleRate(0.5).build();
        // draw 0.9 >= 0.5 -> dropped
        AllStakClient dropClient = client(config, () -> 0.9);
        dropClient.captureException(new RuntimeException("boom"));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/errors")));
        dropClient.shutdown();

        // draw 0.1 < 0.5 -> kept
        AllStakClient keepClient = client(config, () -> 0.1);
        keepClient.captureException(new RuntimeException("boom"));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/errors")));
        keepClient.shutdown();
    }

    // ── tracesSampleRate -> traceparent flag ─────────────────────────────────

    @Test
    void tracesSampleRateNullIsAlwaysSampled() {
        AllStakConfig config = baseConfig().build(); // tracesSampleRate null
        AllStakClient client = client(config, () -> 0.99);
        assertThat(client.isSpanSampled()).isTrue();
        assertThat(client.traceparentSampledFlag()).isEqualTo("01");
        client.shutdown();
    }

    @Test
    void tracesSampleRateDrivesSampledFlagAndSpanDrop() {
        AllStakConfig config = baseConfig().tracesSampleRate(0.5).build();

        // draw 0.9 >= 0.5 -> not sampled
        AllStakClient notSampled = client(config, () -> 0.9);
        assertThat(notSampled.isSpanSampled()).isFalse();
        assertThat(notSampled.traceparentSampledFlag()).isEqualTo("00");
        notSampled.captureSpan("t", "s", null, "op", "d", "ok",
                1, 0, 1, null, null, null);
        notSampled.flush();
        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/spans")));
        notSampled.shutdown();

        // draw 0.1 < 0.5 -> sampled
        AllStakClient sampled = client(config, () -> 0.1);
        assertThat(sampled.isSpanSampled()).isTrue();
        assertThat(sampled.traceparentSampledFlag()).isEqualTo("01");
        sampled.captureSpan("t", "s", null, "op", "d", "ok",
                1, 0, 1, null, null, null);
        wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/spans")));
        sampled.shutdown();
    }

    // ── ordering: sampleRate drop short-circuits beforeSend ──────────────────

    @Test
    void sampleRateDropSkipsBeforeSend() {
        boolean[] hookCalled = {false};
        AllStakConfig config = baseConfig()
                .sampleRate(0.0)
                .beforeSend(event -> { hookCalled[0] = true; return event; })
                .build();
        AllStakClient client = client(config, () -> 0.5);
        client.captureException(new RuntimeException("boom"));
        assertThat(hookCalled[0]).isFalse();
        client.shutdown();
    }

    // ── uncaught handler marks events unhandled ──────────────────────────────

    @Test
    void uncaughtHandlerMarksEventUnhandled() {
        AllStakConfig config = AllStakConfig.builder()
                .apiKey("k")
                .environment("test")
                .flushIntervalMs(200)
                .installUncaughtExceptionHandler(true)
                .build();
        AllStakClient client = client(config, () -> 0.0);
        try {
            Thread.UncaughtExceptionHandler h = Thread.getDefaultUncaughtExceptionHandler();
            h.uncaughtException(Thread.currentThread(), new RuntimeException("uncaught boom"));

            wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                    .withRequestBody(containing("\"message\":\"uncaught boom\""))
                    .withRequestBody(containing("\"mechanism.handled\":false"))
                    .withRequestBody(containing("\"mechanism.type\":\"uncaughtexceptionhandler\"")));
        } finally {
            client.shutdown();
            UncaughtExceptionCapture.uninstall();
        }
    }
}
