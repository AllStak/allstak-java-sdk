package dev.allstak;

import dev.allstak.internal.SdkLogger;
import dev.allstak.model.HttpRequestItem;
import dev.allstak.model.JobHandle;
import dev.allstak.model.UserContext;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p>The ingest host defaults to {@link AllStakConfig#INGEST_HOST} and can be
 * overridden with {@code ALLSTAK_HOST} or {@link AllStakConfig.Builder#host(String)}.
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
        AllStakClient c = CLIENT.get();
        if (c == null) return;
        c.clearBreadcrumbs();
    }

    // =========================================================================
    // User Context
    // =========================================================================

    public static void setUser(UserContext user) {
        AllStakClient c = CLIENT.get();
        if (c == null) return;
        c.setUser(user);
    }

    public static void clearUser() {
        AllStakClient c = CLIENT.get();
        if (c == null) return;
        c.clearUser();
    }

    // =========================================================================
    // Flush & Shutdown
    // =========================================================================

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

    // For testing — allows resetting the singleton
    static void reset() {
        AllStakClient c = CLIENT.getAndSet(null);
        if (c != null) {
            c.shutdown();
        }
    }
}
