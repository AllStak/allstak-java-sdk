package dev.allstak.tracing;

import dev.allstak.AllStakClient;
import dev.allstak.internal.SdkLogger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A first-class tracing span. Created via
 * {@link Transaction#startChild(String, String)} (or, for the root, via
 * {@link AllStakClient}-backed {@link Transaction}). A span records an
 * operation, an optional human-readable description, free-form tags/data, and
 * an outcome {@link SpanStatus}; on {@link #finish()} it is emitted to the
 * AllStak spans ingest through the existing
 * {@link AllStakClient#captureSpan} path with correct trace/span/parent ids,
 * duration, and release/env/session context.
 *
 * <p><b>Ids.</b> Children share the root transaction's 32-hex {@code traceId}
 * and carry their own 16-hex {@code spanId}; {@code parentSpanId} is the
 * id of the span that created them — exactly the linkage the dashboard needs to
 * reconstruct the tree.
 *
 * <p><b>Sampling.</b> The sampling decision is made once, at transaction start,
 * and inherited by every child. An unsampled span is a
 * cheap no-op: tags/data setters and {@code finish} do nothing and emit
 * nothing, so a {@code tracesSampleRate} of 0 imposes near-zero overhead.
 *
 * <p><b>Current span.</b> While a span is open it is registered as the
 * {@linkplain SpanScope#current() current span} for its thread so the existing
 * HTTP/DB interceptors and {@code captureException} can discover and attach to
 * it. The registration is pushed at construction and popped on {@code finish}.
 *
 * <p><b>Thread-safety / fail-open.</b> All mutators are synchronized and every
 * public method swallows its own errors — tracing must never break the host
 * application.
 */
public class Span {

    /** The client the finished span is emitted through. May be {@code null}
     *  when the SDK is not initialised — the span then degrades to a no-op. */
    protected final AllStakClient client;

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final boolean sampled;

    private final String operation;
    private volatile String description;
    private volatile SpanStatus status;

    private final long startTimeMillis;
    private final long startNanos;

    private final Map<String, String> tags = new LinkedHashMap<>();
    private final Map<String, Object> data = new LinkedHashMap<>();

    private final AtomicBoolean finished = new AtomicBoolean(false);

    /**
     * Construct a span. {@code parentSpanId} is {@code null} for a root
     * transaction or the parent span's id for a child. The {@code sampled}
     * decision is computed once by the {@link Transaction} and threaded down.
     * The new span is registered as the current span on the calling thread.
     */
    protected Span(AllStakClient client, String traceId, String spanId,
                   String parentSpanId, String operation, String description,
                   boolean sampled) {
        this.client = client;
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.operation = operation;
        this.description = description;
        this.sampled = sampled;
        this.startTimeMillis = System.currentTimeMillis();
        this.startNanos = System.nanoTime();
        SpanScope.push(this);
    }

    /**
     * Start a child span under this one, sharing the trace id and inheriting
     * the sampling decision. The child's parent is this span. Returns a
     * usable handle even when this span is unsampled (the child is unsampled
     * too and finishing it is a no-op), so callers never null-check.
     */
    public Span startChild(String childOperation, String childDescription) {
        try {
            return new Span(client, traceId, newSpanId(), spanId,
                    childOperation, childDescription, sampled);
        } catch (Throwable t) {
            SdkLogger.debug("startChild failed — returning no-op span: {}", t.getMessage());
            return new Span(null, traceId, newSpanId(), spanId,
                    childOperation, childDescription, false);
        }
    }

    /** Attach a string tag (filterable on the dashboard). Returns {@code this}. */
    public Span setTag(String key, String value) {
        if (key != null) {
            synchronized (tags) { tags.put(key, value); }
        }
        return this;
    }

    /** Attach an arbitrary data value (free-form span data). Returns {@code this}. */
    public Span setData(String key, Object value) {
        if (key != null) {
            synchronized (data) { data.put(key, value); }
        }
        return this;
    }

    /** Set the span outcome status. Returns {@code this}. */
    public Span setStatus(SpanStatus status) {
        this.status = status;
        return this;
    }

    /** Convenience: derive the status from an HTTP response code. Returns {@code this}. */
    public Span setHttpStatus(int httpStatus) {
        this.status = SpanStatus.fromHttpStatus(httpStatus);
        return this;
    }

    /** Update the human-readable description. Returns {@code this}. */
    public Span setDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * Finish the span with its current status (defaulting to {@link SpanStatus#OK}),
     * deregister it as the current span, and — when sampled — emit it to the
     * spans ingest. Idempotent: a second {@code finish} is a no-op.
     */
    public void finish() {
        finish(null);
    }

    /**
     * Finish the span with an explicit terminal status (overriding any status
     * set earlier when non-null). Deregisters the current span and emits when
     * sampled. Idempotent and fail-open.
     */
    public void finish(SpanStatus finalStatus) {
        if (!finished.compareAndSet(false, true)) return;
        SpanScope.pop(this);
        try {
            if (finalStatus != null) this.status = finalStatus;
            if (!sampled || client == null) return; // unsampled / uninitialised → no emit

            long endTimeMillis = System.currentTimeMillis();
            long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
            SpanStatus effective = status != null ? status : SpanStatus.OK;

            Map<String, String> tagsCopy;
            synchronized (tags) { tagsCopy = tags.isEmpty() ? null : new LinkedHashMap<>(tags); }
            Map<String, Object> dataCopy;
            synchronized (data) { dataCopy = data.isEmpty() ? null : new LinkedHashMap<>(data); }

            client.captureSpan(
                    traceId, spanId, parentSpanId,
                    operation, description, effective.wire(),
                    durationMs, startTimeMillis, endTimeMillis,
                    null,                              // service ← config default
                    client.getConfig().getEnvironment(),
                    tagsCopy, dataCopy,
                    true);                             // sampling already decided at tx start
        } catch (Throwable t) {
            SdkLogger.debug("Span finish failed: {}", t.getMessage());
        }
    }

    // ─── Accessors ──────────────────────────────────────────────────────────

    /** 32-hex trace id shared by the whole transaction tree. */
    public String getTraceId() { return traceId; }

    /** 16-hex id of this span. */
    public String getSpanId() { return spanId; }

    /** Id of the span that created this one, or {@code null} for the root. */
    /*@Nullable*/ public String getParentSpanId() { return parentSpanId; }

    public String getOperation() { return operation; }
    /*@Nullable*/ public String getDescription() { return description; }
    /*@Nullable*/ public SpanStatus getStatus() { return status; }

    /** Whether this span will be emitted on finish (the inherited sample bit). */
    public boolean isSampled() { return sampled; }

    /** Whether {@link #finish()} has already run. */
    public boolean isFinished() { return finished.get(); }

    long getStartTimeMillis() { return startTimeMillis; }

    // ─── Id generation ──────────────────────────────────────────────────────

    static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    static String newSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
