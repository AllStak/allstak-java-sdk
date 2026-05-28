package dev.allstak.tracing;

import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.DoubleSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class TracingDecisionsTest {

    private static AllStakClient client(AllStakConfig.Builder b, DoubleSupplier rng) {
        AllStakConfig cfg = b.apiKey("ask_live_test")
                .environment("test")
                .release("v0.0.1")
                .enableAutoSessionTracking(false)
                .installUncaughtExceptionHandler(false)
                .build();
        // Transport is intentionally unreachable; sampling decisions don't need it.
        HttpTransport tx = new HttpTransport("http://127.0.0.1:1", cfg.getApiKey());
        return new AllStakClient(cfg, tx, rng);
    }

    // ─── tracesSampler ────────────────────────────────────────────────────

    @Test
    void tracesSampler_overridesStaticRate() {
        AllStakClient c = client(AllStakConfig.builder()
                .tracesSampleRate(0.0)
                .tracesSampler(ctx -> "checkout".equals(ctx.operation()) ? 1.0 : 0.0),
                () -> 0.5);
        try {
            assertThat(c.isSpanSampled(SamplingContext.builder().operation("checkout").build())).isTrue();
            assertThat(c.isSpanSampled(SamplingContext.builder().operation("healthcheck").build())).isFalse();
        } finally { c.shutdown(); }
    }

    @Test
    void tracesSampler_returningNull_fallsBackToStaticRate() {
        AllStakClient c = client(AllStakConfig.builder()
                .tracesSampleRate(0.5)
                .tracesSampler(ctx -> null),
                () -> 0.49); // < 0.5, so sampled
        try {
            assertThat(c.isSpanSampled(SamplingContext.builder().operation("anything").build())).isTrue();
        } finally { c.shutdown(); }
    }

    @Test
    void parentSampled_honoredWhenNoLocalDecision() {
        AllStakClient c = client(AllStakConfig.builder(), () -> 0.99);
        try {
            // No sampler, no static rate, parent says false → child false.
            assertThat(c.isSpanSampled(SamplingContext.builder().parentSampled(false).build()))
                    .isFalse();
            // Parent says true → child true.
            assertThat(c.isSpanSampled(SamplingContext.builder().parentSampled(true).build()))
                    .isTrue();
        } finally { c.shutdown(); }
    }

    @Test
    void samplerThrowing_fallsBackToStaticRate() {
        AllStakClient c = client(AllStakConfig.builder()
                .tracesSampleRate(1.0)
                .tracesSampler(ctx -> { throw new RuntimeException("nope"); }),
                () -> 0.99);
        try {
            assertThat(c.isSpanSampled(SamplingContext.builder().build())).isTrue();
        } finally { c.shutdown(); }
    }

    // ─── tracePropagationTargets ─────────────────────────────────────────

    @Test
    void propagationTargets_nullList_propagatesEverywhere() {
        TracePropagationDecider d = new TracePropagationDecider(null);
        assertThat(d.shouldPropagate("https://api.allstak.sa/v1/ingest")).isTrue();
        assertThat(d.shouldPropagate("https://random.example.com/foo")).isTrue();
    }

    @Test
    void propagationTargets_substringMatch_matchesByHostFragment() {
        TracePropagationDecider d = new TracePropagationDecider(List.of("api.allstak.sa", "internal.example"));
        assertThat(d.shouldPropagate("https://api.allstak.sa/v1/ingest")).isTrue();
        assertThat(d.shouldPropagate("https://internal.example.com/svc")).isTrue();
        assertThat(d.shouldPropagate("https://third-party.example.org/")).isFalse();
    }

    @Test
    void propagationTargets_regexMatch() {
        TracePropagationDecider d = new TracePropagationDecider(List.of("^https://api\\.[a-z]+\\.com/"));
        assertThat(d.shouldPropagate("https://api.example.com/foo")).isTrue();
        assertThat(d.shouldPropagate("https://api.example.com.evil/foo")).isFalse();
    }

    @Test
    void propagationTargets_emptyTargetUrl_neverPropagates_whenListed() {
        TracePropagationDecider d = new TracePropagationDecider(List.of("api.allstak.sa"));
        assertThat(d.shouldPropagate(null)).isFalse();
        assertThat(d.shouldPropagate("")).isFalse();
    }
}
