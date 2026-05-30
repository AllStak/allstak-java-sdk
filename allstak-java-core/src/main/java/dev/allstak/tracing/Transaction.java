package dev.allstak.tracing;

import dev.allstak.AllStakClient;
import dev.allstak.internal.SdkLogger;

/**
 * The root span of a trace. A
 * transaction carries a human-readable {@code name} (usually the inbound route
 * or job name) and an {@code op} (e.g. {@code "http.server"}, {@code "task"}),
 * makes the trace's sampling decision <b>once</b> at start, and serves as the
 * parent for {@link Span#startChild(String, String) child spans}.
 *
 * <p>The sampling decision reuses the client's existing
 * {@link AllStakClient#isSpanSampled(SamplingContext)} — so {@code tracesSampler},
 * {@code tracesSampleRate}, and an incoming parent-sampled bit all behave
 * exactly as they do for the auto-instrumented per-call spans. Every child
 * inherits the transaction's decision.
 *
 * <p>Create one with {@link AllStakClient}-backed factory methods:
 *
 * <pre>{@code
 * Transaction tx = AllStak.startTransaction("POST /api/orders", "http.server");
 * Span db = tx.startChild("db.query", "INSERT INTO orders");
 * try {
 *     // ... work ...
 *     db.setStatus(SpanStatus.OK);
 * } finally {
 *     db.finish();
 *     tx.setStatus(SpanStatus.OK);
 *     tx.finish();
 * }
 * }</pre>
 */
public final class Transaction extends Span {

    private final String name;

    private Transaction(AllStakClient client, String traceId, String spanId,
                        String parentSpanId, String op, String name, boolean sampled) {
        // The transaction is the root span; its description is its name so the
        // dashboard shows something meaningful for the root row too.
        super(client, traceId, spanId, parentSpanId, op, name, sampled);
        this.name = name;
        // Stamp the active release-health session id so the spans tree can be
        // correlated with the session on the backend. Null when no session is
        // open (auto session tracking disabled or test runtime).
        try {
            String sessionId = client != null ? sessionId(client) : null;
            if (sessionId != null) setTag("session.id", sessionId);
        } catch (Throwable ignored) {
            // Fail-open — session correlation is best-effort.
        }
    }

    /**
     * Start a new root transaction. The sampling decision is computed now and
     * inherited by all children. Always returns a usable handle, even when the
     * SDK is uninitialised (an unsampled no-op transaction) so callers never
     * null-check.
     *
     * @param client SDK client (may be {@code null} before init)
     * @param name   human-readable transaction name (route / job)
     * @param op     operation category ({@code "http.server"}, {@code "task"}, …)
     */
    public static Transaction start(AllStakClient client, String name, String op) {
        return start(client, name, op, null);
    }

    /**
     * Start a transaction with extra {@link SamplingContext} inputs (parent
     * sampled bit, method, url) so a configured {@code tracesSampler} can make
     * a route-aware decision.
     */
    public static Transaction start(AllStakClient client, String name, String op,
                                    SamplingContext context) {
        boolean sampled;
        if (client == null) {
            sampled = false;
        } else {
            try {
                SamplingContext ctx = context != null
                        ? context
                        : SamplingContext.builder().operation(op).name(name).build();
                sampled = client.isSpanSampled(ctx);
            } catch (Throwable t) {
                SdkLogger.debug("Transaction sampling decision failed — defaulting unsampled: {}", t.getMessage());
                sampled = false;
            }
        }
        return new Transaction(client, newTraceId(), newSpanId(), null, op, name, sampled);
    }

    /** The transaction name (inbound route / job name). */
    public String getName() { return name; }

    private static String sessionId(AllStakClient client) {
        // Best-effort: the client exposes the active release-health session id
        // (null when session tracking is off / no session open).
        return client.currentSessionId();
    }
}
