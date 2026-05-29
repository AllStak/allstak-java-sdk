package dev.allstak.tracing;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.function.DoubleSupplier;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Covers the first-class transaction/span tracing API: start → child → finish
 * emits spans with correct parent linkage, status mapping, sampling respected,
 * and current-span propagation via {@link SpanScope}.
 */
class TransactionTracingTest {

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

    @AfterEach
    void tearDown() {
        AllStak.reset();
    }

    private AllStakConfig.Builder baseConfig() {
        return AllStakConfig.builder()
                .apiKey("ask_live_trace_test")
                .environment("test")
                .release("v0.0.1-trace")
                .serviceName("trace-service")
                .enableAutoSessionTracking(false)
                .installUncaughtExceptionHandler(false)
                .flushIntervalMs(200);
    }

    private void initWith(AllStakConfig config, DoubleSupplier rng) {
        HttpTransport transport = new HttpTransport("http://localhost:" + wireMock.port(), config.getApiKey());
        AllStak.init(new AllStakClient(config, transport, rng));
    }

    // ─── start / child / finish + parent linkage ─────────────────────────────

    @Test
    void startChildFinish_emitsSpansWithParentLinkage() {
        initWith(baseConfig().build(), () -> 0.0);

        Transaction tx = AllStak.startTransaction("POST /api/orders", "http.server");
        Span child = tx.startChild("db.query", "INSERT INTO orders");

        // The whole tree shares the transaction's trace id.
        assertThat(child.getTraceId()).isEqualTo(tx.getTraceId());
        // The child's parent is the transaction's span id.
        assertThat(child.getParentSpanId()).isEqualTo(tx.getSpanId());
        // A grandchild parents to the child.
        Span grandchild = child.startChild("cache.get", "redis GET user:1");
        assertThat(grandchild.getParentSpanId()).isEqualTo(child.getSpanId());
        assertThat(grandchild.getTraceId()).isEqualTo(tx.getTraceId());

        grandchild.finish();
        child.finish();
        tx.finish();

        // Three spans emitted, all on the same trace id, with the linkage wired.
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                wireMock.verify(3, postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                        .withRequestBody(containing("\"traceId\":\"" + tx.getTraceId() + "\""))));
        // Root span carries an empty parent; child points at the transaction.
        wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                .withRequestBody(containing("\"spanId\":\"" + tx.getSpanId() + "\""))
                .withRequestBody(containing("\"parentSpanId\":\"\"")));
        wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                .withRequestBody(containing("\"spanId\":\"" + child.getSpanId() + "\""))
                .withRequestBody(containing("\"parentSpanId\":\"" + tx.getSpanId() + "\"")));
    }

    // ─── status mapping + tags/data ──────────────────────────────────────────

    @Test
    void finish_emitsStatusTagsAndData() {
        initWith(baseConfig().build(), () -> 0.0);

        Transaction tx = AllStak.startTransaction("GET /api/widgets", "http.server");
        tx.setTag("route", "/api/widgets");
        tx.setData("widget.count", 7);
        tx.setStatus(SpanStatus.OK);
        tx.finish();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                        .withRequestBody(containing("\"status\":\"ok\""))
                        .withRequestBody(containing("\"operation\":\"http.server\""))
                        .withRequestBody(containing("\"route\":\"/api/widgets\""))
                        .withRequestBody(containing("\"widget.count\":7"))));
    }

    @Test
    void httpStatusMapping_translatesToSpanStatus() {
        // Pure mapping unit checks — no network needed.
        assertThat(SpanStatus.fromHttpStatus(200)).isEqualTo(SpanStatus.OK);
        assertThat(SpanStatus.fromHttpStatus(302)).isEqualTo(SpanStatus.OK);
        assertThat(SpanStatus.fromHttpStatus(401)).isEqualTo(SpanStatus.UNAUTHENTICATED);
        assertThat(SpanStatus.fromHttpStatus(403)).isEqualTo(SpanStatus.PERMISSION_DENIED);
        assertThat(SpanStatus.fromHttpStatus(404)).isEqualTo(SpanStatus.NOT_FOUND);
        assertThat(SpanStatus.fromHttpStatus(429)).isEqualTo(SpanStatus.RESOURCE_EXHAUSTED);
        assertThat(SpanStatus.fromHttpStatus(500)).isEqualTo(SpanStatus.INTERNAL_ERROR);
        assertThat(SpanStatus.fromHttpStatus(503)).isEqualTo(SpanStatus.UNAVAILABLE);
        assertThat(SpanStatus.fromHttpStatus(418)).isEqualTo(SpanStatus.INVALID_ARGUMENT);
    }

    @Test
    void finishWithExplicitStatus_andSetHttpStatus_emitMappedWire() {
        initWith(baseConfig().build(), () -> 0.0);

        Transaction tx = AllStak.startTransaction("call", "http.client");
        Span child = tx.startChild("http.client", "GET https://api.example.com/x");
        child.setHttpStatus(404);
        child.finish();
        tx.finish(SpanStatus.INTERNAL_ERROR);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"spanId\":\"" + child.getSpanId() + "\""))
                    .withRequestBody(containing("\"status\":\"not_found\"")));
            wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"spanId\":\"" + tx.getSpanId() + "\""))
                    .withRequestBody(containing("\"status\":\"internal_error\"")));
        });
    }

    // ─── sampling respected ──────────────────────────────────────────────────

    @Test
    void unsampledTransaction_emitsNothing_andIsNoOp() {
        // tracesSampleRate 0 → never sampled regardless of rng.
        initWith(baseConfig().tracesSampleRate(0.0).build(), () -> 0.0);

        Transaction tx = AllStak.startTransaction("drop me", "task");
        assertThat(tx.isSampled()).isFalse();
        Span child = tx.startChild("child.op", "desc");
        assertThat(child.isSampled()).isFalse(); // inherited
        child.finish();
        tx.finish();

        // Give any (incorrect) async send a chance, then assert nothing went out.
        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/spans")));
    }

    @Test
    void sampledTransaction_childrenInheritSampledBit() {
        initWith(baseConfig().tracesSampleRate(1.0).build(), () -> 0.99);

        Transaction tx = AllStak.startTransaction("keep me", "task");
        assertThat(tx.isSampled()).isTrue();
        Span child = tx.startChild("child.op", "desc");
        assertThat(child.isSampled()).isTrue();
        child.finish();
        tx.finish();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                wireMock.verify(2, postRequestedFor(urlEqualTo("/ingest/v1/spans"))));
    }

    @Test
    void tracesSampler_drivesTransactionDecision() {
        // Sampler keeps "checkout" ops, drops everything else; static rate 0.
        initWith(baseConfig()
                .tracesSampleRate(0.0)
                .tracesSampler(ctx -> "checkout".equals(ctx.operation()) ? 1.0 : 0.0)
                .build(), () -> 0.5);

        Transaction kept = AllStak.startTransaction("POST /checkout", "checkout");
        Transaction dropped = AllStak.startTransaction("GET /health", "health");
        assertThat(kept.isSampled()).isTrue();
        assertThat(dropped.isSampled()).isFalse();
        kept.finish();
        dropped.finish();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/spans"))));
    }

    // ─── current-span propagation ────────────────────────────────────────────

    @Test
    void currentSpan_reflectsActiveTransactionAndChild() {
        initWith(baseConfig().build(), () -> 0.0);

        assertThat(AllStak.getCurrentSpan()).isNull();

        Transaction tx = AllStak.startTransaction("unit", "task");
        assertThat(AllStak.getCurrentSpan()).isSameAs(tx);

        Span child = tx.startChild("inner", "desc");
        assertThat(AllStak.getCurrentSpan()).isSameAs(child);

        child.finish();
        // Finishing the innermost restores the transaction as current.
        assertThat(AllStak.getCurrentSpan()).isSameAs(tx);

        tx.finish();
        assertThat(AllStak.getCurrentSpan()).isNull();
    }

    @Test
    void finishIsIdempotent() {
        initWith(baseConfig().build(), () -> 0.0);

        Transaction tx = AllStak.startTransaction("once", "task");
        tx.finish();
        tx.finish(); // second finish is a no-op — must not emit twice

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/spans"))));
        // Belt-and-suspenders: still exactly one even after the await window.
        wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/spans")));
    }

    @Test
    void beforeInit_startTransactionIsNoOp_neverThrows() {
        // No init — facade must hand back a usable, unsampled no-op handle.
        Transaction tx = AllStak.startTransaction("no client", "task");
        assertThat(tx).isNotNull();
        assertThat(tx.isSampled()).isFalse();
        Span child = tx.startChild("c", "d");
        child.finish();
        tx.finish();
        // current-span still tracked even without a client
        assertThat(AllStak.getCurrentSpan()).isNull();
    }
}
