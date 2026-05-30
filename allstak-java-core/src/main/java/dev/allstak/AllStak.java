package dev.allstak;

import dev.allstak.internal.SdkLogger;
import dev.allstak.model.HttpRequestItem;
import dev.allstak.model.JobHandle;
import dev.allstak.model.UserContext;
import dev.allstak.scope.Scope;
import dev.allstak.scope.Scopes;
import dev.allstak.tracing.SamplingContext;
import dev.allstak.tracing.Span;
import dev.allstak.tracing.SpanScope;
import dev.allstak.tracing.Transaction;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Static facade for the AllStak SDK.
 * Provides a global entry point for all SDK operations.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * AllStak.init(AllStakConfig.builder()
 *     .apiKey("ask_live_xxx")
 *     .environment("production")
 *     .release("v1.0.0")
 *     .build());
 *
 * AllStak.captureException(new RuntimeException("Something went wrong"));
 * AllStak.captureLog("info", "Order processed", Map.of("orderId", "ORD-123"));
 * }</pre>
 *
 * <p>The ingest host defaults to {@link AllStakConfig#INGEST_HOST}. Customers
 * normally configure only their API key and metadata; self-hosted or validation
 * environments can use {@code ALLSTAK_HOST}.
 */
public final class AllStak {

    private static final AtomicReference<AllStakClient> CLIENT = new AtomicReference<>();

    private AllStak() {}

    /**
     * Initialize the SDK. Must be called once at application startup.
     * Subsequent calls are no-ops (a warning is emitted in debug mode).
     */
    public static void init(AllStakConfig config) {
        if (CLIENT.get() != null) {
            SdkLogger.warn("AllStak.init() called more than once — ignoring subsequent call");
            return;
        }
        try {
            AllStakClient client = new AllStakClient(config);
            if (!CLIENT.compareAndSet(null, client)) {
                // Another thread won the race — shut down the one we just created
                client.shutdown();
                SdkLogger.warn("AllStak.init() called concurrently — ignoring duplicate");
            }
        } catch (Exception e) {
            SdkLogger.error("AllStak.init() failed", e);
        }
    }

    /**
     * Initialize with a pre-built client (for testing or advanced usage).
     */
    public static void init(AllStakClient client) {
        if (!CLIENT.compareAndSet(null, client)) {
            SdkLogger.warn("AllStak.init() called more than once — ignoring");
        }
    }

    // =========================================================================
    // Error Capture
    // =========================================================================

    public static void captureException(Throwable throwable) {
        AllStakClient c = CLIENT.get();
        if (c == null) return; // no-op before init
        c.captureException(throwable);
    }

    public static void captureException(Throwable throwable, Map<String, Object> metadata) {
        AllStakClient c = CLIENT.get();
        if (c == null) return;
        c.captureException(throwable, metadata);
    }

    public static void captureException(Throwable throwable, String level, Map<String, Object> metadata) {
        AllStakClient c = CLIENT.get();
        if (c == null) return;
        c.captureException(throwable, level, metadata);
    }

    // =========================================================================
    // Log Capture
    // =========================================================================

    public static void captureLog(String level, String message) {
        AllStakClient c = CLIENT.get();
        if (c == null) return;
        c.captureLog(level, message);
    }

    public static void captureLog(String level, String message, Map<String, Object> metadata) {
        AllStakClient c = CLIENT.get();
        if (c == null) return;
        c.captureLog(level, message, metadata);
    }

    public static void captureLog(String level, String message, String service,
                                  String traceId, Map<String, Object> metadata) {
        AllStakClient c = CLIENT.get();
        if (c == null) return;
        c.captureLog(level, message, service, traceId, metadata);
    }

    // =========================================================================
    // HTTP Request Monitoring
    // =========================================================================

    public static void captureHttpRequest(HttpRequestItem item) {
        AllStakClient c = CLIENT.get();
        if (c == null) return;
        c.captureHttpRequest(item);
    }

    // =========================================================================
    // Cron Job Monitoring
    // =========================================================================

    public static JobHandle startJob(String slug) {
        AllStakClient c = CLIENT.get();
        if (c == null) return new JobHandle(slug, System.currentTimeMillis());
        return c.startJob(slug);
    }

    public static void finishJob(JobHandle handle, String status) {
        AllStakClient c = CLIENT.get();
        if (c == null) return;
        c.finishJob(handle, status);
    }

    public static void finishJob(JobHandle handle, String status, String message) {
        AllStakClient c = CLIENT.get();
        if (c == null) return;
        c.finishJob(handle, status, message);
    }

    // =========================================================================
    // Distributed Tracing — transactions & spans
    //
    // First-class manual tracing API built around transactions and spans.
    // startTransaction opens a root span (one sampling decision, inherited by
    // every child); startChild on the returned handle nests spans. On finish()
    // each sampled span is emitted to /ingest/v1/spans with correct
    // trace/span/parent ids, durations, and release/env/session context, reusing
    // the existing tracesSampleRate/TracesSampler gate. While a span is open it
    // is the discoverable {@link #getCurrentSpan() current span} so interceptors
    // and captureException can attach to it.
    // =========================================================================

    /**
     * Start a new tracing transaction (root span). Always returns a usable
     * handle — before {@code init} (or when unsampled) the handle is a no-op,
     * so callers never null-check. Pair with {@link Transaction#finish()},
     * ideally in a {@code finally}.
     *
     * @param name human-readable transaction name (e.g. {@code "POST /api/orders"})
     * @param op   operation category (e.g. {@code "http.server"}, {@code "task"})
     */
    public static Transaction startTransaction(String name, String op) {
        return Transaction.start(CLIENT.get(), name, op);
    }

    /**
     * Start a transaction with extra {@link SamplingContext} inputs (incoming
     * parent-sampled bit, method, url) so a configured {@code tracesSampler}
     * can make a route-aware sampling decision.
     */
    public static Transaction startTransaction(String name, String op, SamplingContext samplingContext) {
        return Transaction.start(CLIENT.get(), name, op, samplingContext);
    }

    /**
     * The innermost active span on the current thread, or {@code null} when no
     * transaction is in flight. Auto-instrumentation and {@code captureException}
     * can read this to attach to the in-flight trace.
     */
    public static Span getCurrentSpan() {
        return SpanScope.current();
    }

    // =========================================================================
    // Breadcrumbs
    // =========================================================================

    public static void addBreadcrumb(String type, String message, String level, java.util.Map<String, Object> data) {
        AllStakClient c = CLIENT.get();
        if (c == null) return;
        c.addBreadcrumb(type, message, level, data);
    }

    public static void addBreadcrumb(String type, String message) {
        AllStakClient c = CLIENT.get();
        if (c == null) return;
        c.addBreadcrumb(type, message);
    }

    public static void clearBreadcrumbs() {
        Scopes.current().clearBreadcrumbs();
    }

    // =========================================================================
    // User Context — writes to the current scope (Current if pushed,
    // else Isolation). Use withIsolationScope to isolate per-request.
    // =========================================================================

    public static void setUser(UserContext user) {
        Scopes.current().setUser(user);
    }

    public static void clearUser() {
        Scopes.current().setUser(null);
    }

    // =========================================================================
    // Tags / contexts / extras — written to the current scope.
    // =========================================================================

    public static void setTag(String key, String value) {
        Scopes.current().setTag(key, value);
    }

    public static void removeTag(String key) {
        Scopes.current().removeTag(key);
    }

    public static void setContext(String key, Object value) {
        Scopes.current().setContext(key, value);
    }

    public static void setExtra(String key, Object value) {
        Scopes.current().setExtra(key, value);
    }

    // =========================================================================
    // Scope blocks — three-layer Scopes API.
    //
    // withScope: forks Current, runs block, pops (mutations don't escape).
    // withIsolationScope: pushes a fresh isolation scope for a unit of work
    //   (HTTP request, background job) and restores the previous one after.
    // configureScope: runs block against the active scope without forking;
    //   use to accumulate tags/breadcrumbs on the existing isolation scope.
    // =========================================================================

    public static void withScope(Consumer<Scope> block) {
        Scopes.withScope(block);
    }

    public static void withIsolationScope(Consumer<Scope> block) {
        Scopes.withIsolationScope(block);
    }

    public static void configureScope(Consumer<Scope> block) {
        Scopes.configureScope(block);
    }

    // =========================================================================
    // Flush & Shutdown
    // =========================================================================

    // =========================================================================
    // User feedback + attachments
    // =========================================================================

    public static void captureFeedback(dev.allstak.feedback.UserFeedback feedback) {
        AllStakClient c = CLIENT.get();
        if (c == null) return;
        c.captureFeedback(feedback);
    }

    public static void captureAttachment(dev.allstak.feedback.Attachment attachment) {
        AllStakClient c = CLIENT.get();
        if (c == null) return;
        c.captureAttachment(attachment);
    }

    public static void flush() {
        AllStakClient c = CLIENT.get();
        if (c == null) return;
        c.flush();
    }

    public static void shutdown() {
        AllStakClient c = CLIENT.getAndSet(null);
        if (c != null) {
            c.shutdown();
        }
    }

    /**
     * Returns the underlying client, or null if not initialized.
     */
    public static AllStakClient getClient() {
        return CLIENT.get();
    }

    /**
     * Returns true if the SDK has been initialized.
     */
    public static boolean isInitialized() {
        return CLIENT.get() != null;
    }

    /**
     * Reset the SDK to a clean state — primarily for tests. Shuts down the
     * active client (if any) and clears every scope layer. Production
     * code should call {@link #shutdown()} instead; this method is public
     * so test classes outside the {@code dev.allstak} package (e.g. each
     * instrumentation module's own test suite) can reach it.
     */
    public static void reset() {
        AllStakClient c = CLIENT.getAndSet(null);
        if (c != null) {
            c.shutdown();
        }
        Scopes.clear();
        SpanScope.clear();
    }
}
