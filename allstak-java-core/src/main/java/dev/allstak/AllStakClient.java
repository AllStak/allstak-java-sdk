package dev.allstak;

import dev.allstak.buffer.RingBuffer;
import dev.allstak.internal.FlushWorker;
import dev.allstak.internal.SdkLogger;
import dev.allstak.internal.UncaughtExceptionCapture;
import dev.allstak.masking.DataMasker;
import dev.allstak.model.*;
import dev.allstak.transport.HttpTransport;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core AllStak SDK client. Manages buffering, flushing, and sending telemetry data.
 * Thread-safe. One instance per application.
 */
public final class AllStakClient {

    private static final String PATH_ERRORS = "/ingest/v1/errors";
    private static final String PATH_LOGS = "/ingest/v1/logs";
    private static final String PATH_HTTP_REQUESTS = "/ingest/v1/http-requests";
    private static final String PATH_HEARTBEAT = "/ingest/v1/heartbeat";
    private static final String PATH_DB_QUERIES = "/ingest/v1/db";
    private static final String PATH_SPANS = "/ingest/v1/spans";
    private static final String PATH_RELEASES = "/ingest/v1/releases";

    private static final int HTTP_BATCH_MAX = 100;
    private static final int DB_BATCH_MAX = 100;
    private static final int MAX_STACK_FRAMES = 100;
    private static final int BREADCRUMB_BUFFER_SIZE = 50;

    private static final ThreadLocal<RequestContext> currentRequestContext = new ThreadLocal<>();

    public static void setRequestContext(RequestContext ctx) { currentRequestContext.set(ctx); }
    public static void clearRequestContext() { currentRequestContext.remove(); }
    public static RequestContext getRequestContext() { return currentRequestContext.get(); }

    private final AllStakConfig config;
    private final HttpTransport transport;

    // Buffers
    private final RingBuffer<LogEvent> logBuffer;
    private final RingBuffer<HttpRequestItem> httpBuffer;
    private final RingBuffer<Breadcrumb> breadcrumbBuffer;
    private final RingBuffer<DatabaseQueryItem> dbQueryBuffer;

    // Flush workers
    private final FlushWorker<LogEvent> logFlusher;
    private final FlushWorker<HttpRequestItem> httpFlusher;
    private final FlushWorker<DatabaseQueryItem> dbQueryFlusher;

    // User context (set globally)
    private volatile UserContext currentUser;

    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private boolean uncaughtHandlerInstalled = false;

    // Sampling RNG seam — returns a value in [0.0, 1.0). Injectable for tests.
    private final java.util.function.DoubleSupplier sampler;

    public AllStakClient(AllStakConfig config) {
        this(config, new HttpTransport(config.getHost(), config.getApiKey()));
    }

    // Visible for testing — allows injecting a custom transport
    public AllStakClient(AllStakConfig config, HttpTransport transport) {
        this(config, transport, () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble());
    }

    // Visible for testing — allows injecting a deterministic sampling source
    public AllStakClient(AllStakConfig config, HttpTransport transport,
                         java.util.function.DoubleSupplier sampler) {
        this.config = config;
        this.transport = transport;
        this.sampler = sampler;

        SdkLogger.setDebug(config.isDebug());

        this.logBuffer = new RingBuffer<>(config.getBufferSize());
        this.httpBuffer = new RingBuffer<>(config.getBufferSize());
        this.breadcrumbBuffer = new RingBuffer<>(BREADCRUMB_BUFFER_SIZE);
        this.dbQueryBuffer = new RingBuffer<>(config.getBufferSize());

        // Log flush worker — sends one log per request
        this.logFlusher = new FlushWorker<>("logs", logBuffer, logs -> {
            for (LogEvent log : logs) {
                transport.send(PATH_LOGS, log);
            }
            return true;
        });

        // HTTP request flush worker — sends in batches of up to 100
        this.httpFlusher = new FlushWorker<>("http-requests", httpBuffer, items -> {
            for (int i = 0; i < items.size(); i += HTTP_BATCH_MAX) {
                List<HttpRequestItem> batch = items.subList(i, Math.min(i + HTTP_BATCH_MAX, items.size()));
                transport.send(PATH_HTTP_REQUESTS, new HttpRequestBatch(batch));
            }
            return true;
        });

        // DB query flush worker — sends in batches of up to 100
        this.dbQueryFlusher = new FlushWorker<>("db-queries", dbQueryBuffer, items -> {
            for (int i = 0; i < items.size(); i += DB_BATCH_MAX) {
                List<DatabaseQueryItem> batch = items.subList(i, Math.min(i + DB_BATCH_MAX, items.size()));
                transport.send(PATH_DB_QUERIES, new DatabaseQueryBatch(batch));
            }
            return true;
        });

        // Start flush workers
        logFlusher.start(config.getFlushIntervalMs());
        httpFlusher.start(config.getFlushIntervalMs());
        dbQueryFlusher.start(config.getFlushIntervalMs());
        registerRuntimeRelease();

        // Install the global uncaught-exception handler for background / non-web
        // threads (chains the previously-installed default handler). Opt-out via
        // config. Idempotent — a second client install is a no-op.
        if (config.isInstallUncaughtExceptionHandler()) {
            this.uncaughtHandlerInstalled = UncaughtExceptionCapture.install(this);
        }

        SdkLogger.debug("AllStak SDK initialized — host={}, env={}, release={}",
                config.getHost(), config.getEnvironment(), config.getRelease());
    }

    private void registerRuntimeRelease() {
        if (!config.isAutoRegisterRelease() || config.getRelease() == null || config.getRelease().isBlank()) return;
        if (isLikelyTestRuntime()) return;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", config.getRelease());
        payload.put("environment", config.getEnvironment());
        payload.put("commitSha", config.getCommitSha());
        payload.put("branch", config.getBranch());
        payload.put("author", null);
        payload.put("message", null);

        Thread thread = new Thread(() -> {
            try {
                transport.send(PATH_RELEASES, payload);
            } catch (Throwable t) {
                SdkLogger.debug("Release registration failed: {}", t.getMessage());
            }
        }, "allstak-release-registration");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean isLikelyTestRuntime() {
        String classpath = System.getProperty("java.class.path", "").toLowerCase(Locale.ROOT);
        return classpath.contains("surefire")
                || classpath.contains("junit")
                || classpath.contains("test-classes")
                || System.getProperty("surefire.test.class.path") != null;
    }

    // =========================================================================
    // Error Capture — sent immediately (errors are urgent)
    // =========================================================================

    public void captureException(Throwable throwable) {
        captureException(throwable, null);
    }

    public void captureException(Throwable throwable, Map<String, Object> metadata) {
        captureException(throwable, "error", metadata);
    }

    public void captureException(Throwable throwable, String level, Map<String, Object> metadata) {
        try {
            if (shutdown.get() || transport.isDisabled()) return;

            // 1. sample_rate drop first — skip dropped events before any work.
            if (isSampledOut(config.getSampleRate())) {
                SdkLogger.debug("Exception dropped by sampleRate={}", config.getSampleRate());
                return;
            }

            String exceptionClass = throwable.getClass().getName();
            String message = throwable.getMessage() != null ? throwable.getMessage() : exceptionClass;
            List<String> stackTrace = extractStackTrace(throwable);

            // Merge release-tracking tags (sdk.name/version, platform, dist,
            // commit.sha/branch) into metadata. Caller-supplied metadata wins.
            // Masking is deferred until AFTER beforeSend so the callback sees
            // the real values and the order is: sample_rate -> beforeSend ->
            // masking -> transport.
            Map<String, Object> mergedMetaErr = new java.util.LinkedHashMap<>();
            for (var e : config.releaseTags().entrySet()) mergedMetaErr.put(e.getKey(), e.getValue());
            if (metadata != null) mergedMetaErr.putAll(metadata);

            // Attach request context if available on this thread
            RequestContext reqCtx = currentRequestContext.get();
            String traceId = reqCtx != null ? reqCtx.getTraceId() : null;
            RequestContext eventReqCtx = reqCtx != null
                    ? RequestContext.of(
                            reqCtx.getMethod(),
                            reqCtx.getPath(),
                            reqCtx.getHost(),
                            reqCtx.getStatusCode(),
                            reqCtx.getUserAgent(),
                            traceId)
                    : null;

            // Drain breadcrumbs and attach to error event
            List<Breadcrumb> breadcrumbs = breadcrumbBuffer.drain();
            List<Breadcrumb> eventBreadcrumbs = breadcrumbs.isEmpty() ? null : breadcrumbs;

            // Phase 2 — build structured frames from the JVM stack trace
            // alongside the legacy v1 string list. Each Throwable.StackTraceElement
            // is already structured: class, method, file, line. No regex parsing.
            java.util.List<dev.allstak.model.Frame> frames = new java.util.ArrayList<>();
            int frameCount = 0;
            for (StackTraceElement st : throwable.getStackTrace()) {
                if (frameCount++ >= MAX_STACK_FRAMES) break;
                String fn = st.getClassName() + "." + st.getMethodName();
                String file = st.getFileName();
                boolean inApp = isInApp(st.getClassName());
                frames.add(new dev.allstak.model.Frame(
                        file, file, fn,
                        st.getLineNumber() > 0 ? st.getLineNumber() : null,
                        null, inApp, "java", null));
            }

            ErrorEvent event = new ErrorEvent(
                    exceptionClass,
                    message,
                    stackTrace,
                    level != null ? level : "error",
                    config.getEnvironment(),
                    config.getRelease(),
                    null, // sessionId — not applicable for server SDK
                    currentUser,
                    mergedMetaErr,
                    traceId,
                    eventReqCtx,
                    eventBreadcrumbs,
                    config.getPlatform(),
                    config.getSdkName(),
                    config.getSdkVersion(),
                    config.getDist(),
                    frames.isEmpty() ? null : frames
            );

            // 2. beforeSend — may modify or drop (null). Fail-open if it throws.
            ErrorEvent processed = applyBeforeSend(event);
            if (processed == null) {
                SdkLogger.debug("Exception dropped by beforeSend");
                return;
            }

            // 3. masking — scrub PII from metadata after beforeSend ran.
            ErrorEvent masked = withMaskedMetadata(processed);

            // 4. transport — errors are sent immediately, no buffering.
            transport.send(PATH_ERRORS, masked);
        } catch (Exception e) {
            SdkLogger.debug("Failed to capture exception: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Log Capture — buffered, flushed on timer/capacity
    // =========================================================================

    public void captureLog(String level, String message) {
        captureLog(level, message, null);
    }

    public void captureLog(String level, String message, Map<String, Object> metadata) {
        captureLog(level, message, null, null, metadata);
    }

    public void captureLog(String level, String message, String service,
                           String traceId, Map<String, Object> metadata) {
        captureLog(level, message, service, traceId, null, null, null, null, null, metadata);
    }

    public void captureLog(String level, String message, String service,
                           String traceId, String environment, String spanId,
                           String requestId, String userId, String errorId,
                           Map<String, Object> metadata) {
        try {
            if (shutdown.get() || transport.isDisabled()) return;

            if (!isValidLogLevel(level)) {
                SdkLogger.debug("Invalid log level '{}' — dropping log", level);
                return;
            }

            // 1. sample_rate drop first.
            if (isSampledOut(config.getSampleRate())) {
                SdkLogger.debug("Log dropped by sampleRate={}", config.getSampleRate());
                return;
            }

            if (config.isAutoBreadcrumbs() && ("warn".equals(level) || "error".equals(level) || "fatal".equals(level))) {
                breadcrumbBuffer.add(new Breadcrumb("log", message, level, metadata));
            }

            // Merge release-tracking tags into log metadata too. Masking is
            // deferred until after beforeSend (sample_rate -> beforeSend ->
            // masking -> transport).
            Map<String, Object> mergedMetaLog = new java.util.LinkedHashMap<>();
            for (var e : config.releaseTags().entrySet()) mergedMetaLog.put(e.getKey(), e.getValue());
            if (metadata != null) mergedMetaLog.putAll(metadata);
            String svc = service != null ? service : config.getServiceName();
            String env = environment != null ? environment : config.getEnvironment();

            LogEvent event = new LogEvent(level, message, svc, traceId, env, spanId,
                    requestId, userId, errorId, mergedMetaLog, config.getRelease());

            // 2. beforeSend — may modify or drop (null). Fail-open if it throws.
            LogEvent processed = applyBeforeSend(event);
            if (processed == null) {
                SdkLogger.debug("Log dropped by beforeSend");
                return;
            }

            // 3. masking — scrub PII from metadata after beforeSend ran.
            LogEvent masked = withMaskedMetadata(processed);

            // 4. transport — buffered, flushed on timer/capacity.
            logBuffer.add(masked);
            logFlusher.checkCapacityFlush();
        } catch (Exception e) {
            SdkLogger.debug("Failed to capture log: {}", e.getMessage());
        }
    }

    // =========================================================================
    // HTTP Request Monitoring — buffered, flushed in batches of up to 100
    // =========================================================================

    public void captureHttpRequest(HttpRequestItem item) {
        try {
            if (shutdown.get() || transport.isDisabled()) return;

            // Strip query parameters from path, preserve all other fields
            HttpRequestItem sanitized = HttpRequestItem.builder()
                    .traceId(item.getTraceId())
                    .requestId(item.getRequestId())
                    .spanId(item.getSpanId())
                    .parentSpanId(item.getParentSpanId())
                    .direction(item.getDirection())
                    .method(item.getMethod())
                    .host(item.getHost())
                    .path(DataMasker.stripSensitiveQueryParams(item.getPath()))
                    .statusCode(item.getStatusCode())
                    .durationMs(item.getDurationMs())
                    .requestSize(item.getRequestSize())
                    .responseSize(item.getResponseSize())
                    .userId(item.getUserId())
                    .errorFingerprint(item.getErrorFingerprint())
                    .timestamp(item.getTimestamp())
                    .requestHeaders(item.getRequestHeaders())
                    .responseHeaders(item.getResponseHeaders())
                    .requestBody(item.getRequestBody())
                    .responseBody(item.getResponseBody())
                    .requestBodyCaptureStatus(item.getRequestBodyCaptureStatus())
                    .responseBodyCaptureStatus(item.getResponseBodyCaptureStatus())
                    .requestBodyCaptureReason(item.getRequestBodyCaptureReason())
                    .responseBodyCaptureReason(item.getResponseBodyCaptureReason())
                    // Default release/environment to config-level values when
                    // the caller didn't set them explicitly on the item. This
                    // is what makes auto-instrumented inbound requests carry
                    // the release tag without every integration plugin
                    // having to re-thread it.
                    .environment(item.getEnvironment() != null ? item.getEnvironment() : config.getEnvironment())
                    .release(item.getRelease() != null ? item.getRelease() : config.getRelease())
                    .build();

            httpBuffer.add(sanitized);
            httpFlusher.checkCapacityFlush();
        } catch (Exception e) {
            SdkLogger.debug("Failed to capture HTTP request: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Database Query Monitoring — buffered, flushed in batches of up to 100
    // =========================================================================

    public void captureDbQuery(DatabaseQueryItem item) {
        try {
            if (shutdown.get() || transport.isDisabled()) return;

            // Enrich with config defaults if not set
            DatabaseQueryItem enriched = DatabaseQueryItem.builder()
                    .normalizedQuery(item.getNormalizedQuery())
                    .queryHash(item.getQueryHash())
                    .queryType(item.getQueryType())
                    .durationMs(item.getDurationMs())
                    .timestampMillis(item.getTimestampMillis())
                    .status(item.getStatus())
                    .errorMessage(item.getErrorMessage())
                    .databaseName(item.getDatabaseName())
                    .databaseType(item.getDatabaseType())
                    .service(item.getService() != null ? item.getService() : config.getServiceName())
                    .environment(item.getEnvironment() != null ? item.getEnvironment() : config.getEnvironment())
                    .traceId(item.getTraceId())
                    .spanId(item.getSpanId())
                    .rowsAffected(item.getRowsAffected())
                    .release(item.getRelease() != null ? item.getRelease() : config.getRelease())
                    .build();

            dbQueryBuffer.add(enriched);
            dbQueryFlusher.checkCapacityFlush();
        } catch (Exception e) {
            SdkLogger.debug("Failed to capture DB query: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Cron Job Monitoring — sent immediately after job completes
    // =========================================================================

    public JobHandle startJob(String slug) {
        if (slug == null || !slug.matches("^[a-z0-9\\-]+$")) {
            SdkLogger.debug("Invalid cron slug '{}' — must be lowercase alphanumeric with hyphens", slug);
            return new JobHandle(slug != null ? slug : "unknown", System.currentTimeMillis());
        }
        return new JobHandle(slug, System.currentTimeMillis());
    }

    public void finishJob(JobHandle handle, String status) {
        finishJob(handle, status, null);
    }

    public void finishJob(JobHandle handle, String status, String message) {
        try {
            if (shutdown.get() || transport.isDisabled()) return;

            long durationMs = System.currentTimeMillis() - handle.getStartTimeMs();
            String normalizedStatus = status != null ? status.toLowerCase() : "success";

            HeartbeatEvent event = new HeartbeatEvent(
                    handle.getSlug(),
                    normalizedStatus,
                    durationMs,
                    message,
                    config.getEnvironment(),
                    config.getRelease()
            );

            // Heartbeats are sent immediately
            transport.send(PATH_HEARTBEAT, event);
        } catch (Exception e) {
            SdkLogger.debug("Failed to send heartbeat: {}", e.getMessage());
        }
    }

    // =========================================================================
    // User Context
    // =========================================================================

    public void setUser(UserContext user) {
        this.currentUser = user;
    }

    public void clearUser() {
        this.currentUser = null;
    }

    // =========================================================================
    // Breadcrumbs
    // =========================================================================

    /**
     * Add a breadcrumb to the ring buffer. Breadcrumbs are attached to the next
     * captured error event and then cleared.
     *
     * @param type    Category of the breadcrumb ("http", "log", "ui", "navigation", "query", "default").
     * @param message Human-readable description.
     * @param level   Severity level ("info", "warn", "error", "debug"). Defaults to "info" if null.
     * @param data    Optional key-value data.
     */
    public void addBreadcrumb(String type, String message, String level, Map<String, Object> data) {
        if (shutdown.get()) return;
        Map<String, Object> safeData = DataMasker.maskMetadata(data);
        breadcrumbBuffer.add(new Breadcrumb(type, message, level, safeData));
    }

    public void addBreadcrumb(String type, String message) {
        addBreadcrumb(type, message, null, null);
    }

    /**
     * Clear all breadcrumbs from the buffer.
     */
    public void clearBreadcrumbs() {
        breadcrumbBuffer.drain();
    }

    // =========================================================================
    // Span Capture — sent immediately
    // =========================================================================

    public void captureSpan(String traceId, String spanId, String parentSpanId,
                            String operation, String description, String status,
                            long durationMs, long startTimeMillis, long endTimeMillis,
                            String service, String environment, Map<String, String> tags) {
        if (shutdown.get() || transport.isDisabled()) return;
        // Span sampling — when tracesSampleRate is configured, drop unsampled
        // spans. Null keeps the legacy always-on behavior.
        if (!isSpanSampled()) {
            SdkLogger.debug("Span dropped by tracesSampleRate={}", config.getTracesSampleRate());
            return;
        }
        try {
            Map<String, Object> span = new LinkedHashMap<>();
            span.put("traceId", traceId);
            span.put("spanId", spanId);
            span.put("parentSpanId", parentSpanId != null ? parentSpanId : "");
            span.put("operation", operation);
            span.put("description", description != null ? description : "");
            span.put("status", status);
            span.put("durationMs", durationMs);
            span.put("startTimeMillis", startTimeMillis);
            span.put("endTimeMillis", endTimeMillis);
            span.put("service", service != null ? service : config.getServiceName());
            span.put("environment", environment != null ? environment : config.getEnvironment());
            span.put("release", config.getRelease() != null ? config.getRelease() : "");
            span.put("tags", tags != null ? tags : Map.of());
            span.put("data", "");

            Map<String, Object> payload = Map.of("spans", List.of(span));
            transport.send(PATH_SPANS, payload);
        } catch (Exception e) {
            SdkLogger.debug("Failed to capture span: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Flush & Shutdown
    // =========================================================================

    public void flush() {
        try {
            logFlusher.flush();
            httpFlusher.flush();
            dbQueryFlusher.flush();
        } catch (Exception e) {
            SdkLogger.debug("Flush failed: {}", e.getMessage());
        }
    }

    /**
     * Alias for {@link #shutdown()} — provided for convenience.
     */
    public void destroy() {
        shutdown();
    }

    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            SdkLogger.debug("AllStak SDK shutting down...");
            if (uncaughtHandlerInstalled) {
                UncaughtExceptionCapture.uninstall();
                uncaughtHandlerInstalled = false;
            }
            logFlusher.shutdown();
            httpFlusher.shutdown();
            dbQueryFlusher.shutdown();
            SdkLogger.debug("AllStak SDK shut down complete");
        }
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public AllStakConfig getConfig() { return config; }
    public HttpTransport getTransport() { return transport; }
    public boolean isShutdown() { return shutdown.get(); }

    // =========================================================================
    // Internal
    // =========================================================================

    /**
     * Heuristic for whether a stack frame is in the customer's app or in
     * a third-party / JDK library. Conservative: only marks JDK + common
     * frameworks as out-of-app so the dashboard surfaces customer code
     * by default rather than hiding it under a toggle.
     */
    private static boolean isInApp(String className) {
        if (className == null) return true;
        return !(className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("com.sun.")
                || className.startsWith("org.springframework.")
                || className.startsWith("org.apache.")
                || className.startsWith("org.eclipse.")
                || className.startsWith("io.netty.")
                || className.startsWith("ch.qos.logback.")
                || className.startsWith("dev.allstak."));
    }

    private List<String> extractStackTrace(Throwable throwable) {
        List<String> result = new ArrayList<>();
        int totalFrames = 0;
        Throwable current = throwable;
        boolean isFirst = true;

        while (current != null && totalFrames < MAX_STACK_FRAMES) {
            String header;
            if (isFirst) {
                header = current.getClass().getName()
                        + (current.getMessage() != null ? ": " + current.getMessage() : "");
                isFirst = false;
            } else {
                header = "Caused by: " + current.getClass().getName()
                        + (current.getMessage() != null ? ": " + current.getMessage() : "");
            }
            result.add(header);

            StackTraceElement[] elements = current.getStackTrace();
            for (StackTraceElement el : elements) {
                if (totalFrames >= MAX_STACK_FRAMES) break;
                result.add(String.format("at %s.%s(%s:%d)",
                        el.getClassName(), el.getMethodName(),
                        el.getFileName() != null ? el.getFileName() : "Unknown",
                        el.getLineNumber()));
                totalFrames++;
            }

            current = current.getCause();
        }

        return result;
    }

    // =========================================================================
    // Sampling & beforeSend pipeline
    // =========================================================================

    /**
     * Deterministic-at-the-bounds sampling decision for error/message events.
     * rate &gt;= 1.0 always keeps, rate &lt;= 0.0 always drops, otherwise a single
     * RNG draw decides. The RNG is injectable (see test constructor).
     *
     * @return true if the event should be DROPPED.
     */
    private boolean isSampledOut(double rate) {
        if (rate >= 1.0) return false;
        if (rate <= 0.0) return true;
        return sampler.getAsDouble() >= rate;
    }

    /**
     * Span-creation sampling decision driven by {@code tracesSampleRate}.
     * Null = always sampled (legacy always-on behavior).
     *
     * @return true if the span IS sampled (created / traceparent flag {@code -01}).
     */
    public boolean isSpanSampled() {
        Double rate = config.getTracesSampleRate();
        if (rate == null) return true;          // legacy always-on
        if (rate >= 1.0) return true;
        if (rate <= 0.0) return false;
        return sampler.getAsDouble() < rate;
    }

    /**
     * The W3C traceparent sampled flag for the current config: {@code "01"} when
     * the span is sampled, {@code "00"} when not. Use to build the trace flags
     * byte in a {@code traceparent} header.
     */
    public String traceparentSampledFlag() {
        return isSpanSampled() ? "01" : "00";
    }

    @SuppressWarnings("unchecked")
    private <T> T applyBeforeSend(T event) {
        java.util.function.Function<Object, Object> hook = config.getBeforeSend();
        if (hook == null) return event;
        try {
            Object result = hook.apply(event);
            // null is a deliberate drop; any non-null is the (possibly modified) event.
            return (T) result;
        } catch (Throwable t) {
            // Fail-open: log and send the original event unmodified.
            SdkLogger.debug("beforeSend threw — sending original event: {}", t.getMessage());
            return event;
        }
    }

    private static ErrorEvent withMaskedMetadata(ErrorEvent e) {
        if (e.getMetadata() == null) return e;
        Map<String, Object> masked = DataMasker.maskMetadata(e.getMetadata());
        return new ErrorEvent(
                e.getExceptionClass(), e.getMessage(), e.getStackTrace(), e.getLevel(),
                e.getEnvironment(), e.getRelease(), e.getSessionId(), e.getUser(),
                masked, e.getTraceId(), e.getRequestContext(), e.getBreadcrumbs(),
                e.getPlatform(), e.getSdkName(), e.getSdkVersion(), e.getDist(), e.getFrames());
    }

    private static LogEvent withMaskedMetadata(LogEvent e) {
        if (e.getMetadata() == null) return e;
        Map<String, Object> masked = DataMasker.maskMetadata(e.getMetadata());
        return new LogEvent(e.getLevel(), e.getMessage(), e.getService(), e.getTraceId(),
                e.getEnvironment(), e.getSpanId(), e.getRequestId(), e.getUserId(),
                e.getErrorId(), masked, e.getRelease());
    }

    private static boolean isValidLogLevel(String level) {
        return level != null && switch (level) {
            case "debug", "info", "warn", "error", "fatal" -> true;
            default -> false;
        };
    }
}
