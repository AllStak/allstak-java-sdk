package dev.allstak;

import dev.allstak.buffer.RingBuffer;
import dev.allstak.internal.FlushWorker;
import dev.allstak.internal.SdkLogger;
import dev.allstak.internal.UncaughtExceptionCapture;
import dev.allstak.masking.DataMasker;
import dev.allstak.model.*;
import dev.allstak.scope.MergedScope;
import dev.allstak.scope.Scopes;
import dev.allstak.session.SessionStatus;
import dev.allstak.session.SessionTracker;
import dev.allstak.spool.EventSpool;
import dev.allstak.transport.HttpTransport;
import dev.allstak.transport.SendResult;
import dev.allstak.transport.TransportDiagnostics;
import dev.allstak.tracing.SpanScope;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final String PATH_FEEDBACK = "/ingest/v1/feedback";
    /** Generic SDK attachment path. The dedicated
     *  {@code /ingest/v1/errors/{eventId}/attachments} screenshot path
     *  exists too but enforces image-only MIME + redaction modes; the
     *  SDK posts here so logs, configs, and other structured context
     *  go through. {@code eventId} travels in the body. */
    private static final String PATH_ATTACHMENTS = "/ingest/v1/attachments";
    /** Raw JFR chunks travel through a dedicated route so the parser
     *  fan-out happens server-side. */
    private static final String PATH_PROFILES_JFR = "/ingest/v1/profiles/jfr";

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

    /**
     * Offline / persistent event queue. Non-null and {@link EventSpool#isAvailable()
     * available} only when {@link AllStakConfig#isEnableOfflineQueue()} is true
     * and the spool directory is writable. Persists already-scrubbed
     * error/log/span/http/db envelopes that could not be delivered; never
     * session lifecycle calls. Degrades silently to in-memory when unavailable.
     */
    private final EventSpool spool;

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
    private final AtomicLong eventsCaptured = new AtomicLong();
    private final AtomicLong eventsDropped = new AtomicLong();
    private final AtomicLong eventsPersisted = new AtomicLong();
    private final AtomicLong eventsReplayed = new AtomicLong();

    /**
     * Release-health session tracker. Non-null when
     * {@link AllStakConfig#isEnableAutoSessionTracking()} is true (default).
     * One session per JVM lifetime in the current single-mode implementation.
     */
    private final SessionTracker sessionTracker;

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

        // Offline spool — persists un-sent error/log/span/http/db envelopes so
        // they survive restarts and outages. Construction never throws and a
        // non-writable dir leaves it !available (silent in-memory fallback).
        this.spool = buildSpool(config, transport);

        // Log flush worker — sends one log per request; un-deliverable logs are
        // spooled for the next drain.
        this.logFlusher = new FlushWorker<>("logs", logBuffer, logs -> {
            for (LogEvent log : logs) {
                sendOrSpool(PATH_LOGS, log);
            }
            return true;
        });

        // HTTP request flush worker — sends in batches of up to 100
        this.httpFlusher = new FlushWorker<>("http-requests", httpBuffer, items -> {
            for (int i = 0; i < items.size(); i += HTTP_BATCH_MAX) {
                List<HttpRequestItem> batch = items.subList(i, Math.min(i + HTTP_BATCH_MAX, items.size()));
                sendOrSpool(PATH_HTTP_REQUESTS, new HttpRequestBatch(batch));
            }
            return true;
        });

        // DB query flush worker — sends in batches of up to 100
        this.dbQueryFlusher = new FlushWorker<>("db-queries", dbQueryBuffer, items -> {
            for (int i = 0; i < items.size(); i += DB_BATCH_MAX) {
                List<DatabaseQueryItem> batch = items.subList(i, Math.min(i + DB_BATCH_MAX, items.size()));
                sendOrSpool(PATH_DB_QUERIES, new DatabaseQueryBatch(batch));
            }
            return true;
        });

        // Start flush workers
        logFlusher.start(config.getFlushIntervalMs());
        httpFlusher.start(config.getFlushIntervalMs());
        dbQueryFlusher.start(config.getFlushIntervalMs());
        registerRuntimeRelease();

        // Open the release-health session for this JVM. Skipped under
        // test classpaths (mirrors the release-registration guard) so
        // unit tests don't hit /ingest/v1/sessions/start.
        if (config.isEnableAutoSessionTracking() && !isLikelyTestRuntime()) {
            this.sessionTracker = new SessionTracker(config, transport, SessionTracker.defaultStatePath(config));
            this.sessionTracker.start(initialUserId());
        } else {
            this.sessionTracker = null;
        }

        // Install the global uncaught-exception handler for background / non-web
        // threads (chains the previously-installed default handler). Opt-out via
        // config. Idempotent — a second client install is a no-op.
        if (config.isInstallUncaughtExceptionHandler()) {
            this.uncaughtHandlerInstalled = UncaughtExceptionCapture.install(this);
        }

        SdkLogger.debug("AllStak SDK initialized — host={}, env={}, release={}",
                config.getHost(), config.getEnvironment(), config.getRelease());

        // Drain any envelopes persisted by a previous run / before the last
        // outage. Runs on a daemon thread so init never blocks on the network.
        drainSpoolAsync();
    }

    /**
     * Build the offline spool from config, fail-open. Returns {@code null} when
     * the feature is disabled; an {@link EventSpool} otherwise (which itself
     * reports {@link EventSpool#isAvailable()} = false when the dir is not
     * writable, so all spool ops become no-ops). Never throws.
     */
    private static EventSpool buildSpool(AllStakConfig config, HttpTransport transport) {
        if (!config.isEnableOfflineQueue()) return null;
        // Under a unit-test classpath, only build a spool when the caller gave
        // an explicit directory (mirrors the session/release-registration test
        // guards). This keeps the shared default temp dir out of the existing
        // request-count assertions while letting the spool's own tests opt in
        // by configuring offlineQueueDir to a @TempDir.
        if (isLikelyTestRuntime() && (config.getOfflineQueueDir() == null || config.getOfflineQueueDir().isBlank())) {
            return null;
        }
        try {
            java.nio.file.Path dir = EventSpool.resolveDir(config.getOfflineQueueDir(), config.getApiKey());
            return new EventSpool(
                    dir,
                    config.getOfflineQueueMaxEntries(),
                    config.getOfflineQueueMaxBytes(),
                    config.getOfflineQueueMaxAgeMs(),
                    transport.getObjectMapper());
        } catch (Throwable t) {
            SdkLogger.debug("Offline spool init failed — in-memory only: {}", t.getMessage());
            return null;
        }
    }

    /**
     * Send a (already PII-scrubbed) payload through the transport and, only on a
     * transient failure (offline, retries exhausted, 5xx/429), persist it to the
     * offline spool so it survives a restart or outage. Permanent failures
     * (4xx, auth-disabled) are dropped as before. The scrub authority is the
     * existing capture pipeline — the spool stores exactly what would have gone
     * on the wire. Never throws.
     *
     * @return true if the send was accepted (2xx)
     */
    private boolean sendOrSpool(String path, Object maskedPayload) {
        SendResult result = transport.sendWithResult(path, maskedPayload);
        if (result == SendResult.TRANSIENT && spool != null) {
            boolean persisted = false;
            try {
                // Serialize via the transport's own mapper so the spooled bytes
                // match the wire form exactly (already scrubbed upstream).
                com.fasterxml.jackson.databind.JsonNode node =
                        transport.getObjectMapper().valueToTree(maskedPayload);
                persisted = spool.persist(path, node);
            } catch (Throwable t) {
                SdkLogger.debug("Spool persist skipped for {}: {}", path, t.getMessage());
            }
            if (persisted) eventsPersisted.incrementAndGet();
            else eventsDropped.incrementAndGet();
        } else if (result == SendResult.TRANSIENT) {
            eventsDropped.incrementAndGet();
        }
        return result.isAccepted();
    }

    /**
     * Asynchronously replay persisted envelopes through the existing transport
     * (which applies the same retry/backoff/disable behavior). An entry is
     * removed only once it is accepted (2xx) or permanently undeliverable (4xx
     * other than 429); transient failures leave it on disk for a later drain so
     * a continuing outage does not lose data. Fail-open throughout.
     */
    private void drainSpoolAsync() {
        if (spool == null || !spool.isAvailable()) return;
        Thread t = new Thread(() -> {
            try {
                List<EventSpool.Handle> handles = spool.load();
                if (handles.isEmpty()) return;
                SdkLogger.debug("Draining {} persisted event(s) from offline spool", handles.size());
                for (EventSpool.Handle h : handles) {
                    if (shutdown.get() || transport.isDisabled()) break;
                    try {
                        String json = transport.getObjectMapper().writeValueAsString(h.payload());
                        SendResult r = transport.sendRawJson(h.path(), json);
                        // Remove only on a terminal outcome; keep TRANSIENT for
                        // the next drain so a continuing outage is not lost.
                        if (r == SendResult.ACCEPTED || r == SendResult.PERMANENT) {
                            if (r == SendResult.ACCEPTED) eventsReplayed.incrementAndGet();
                            spool.remove(h);
                        }
                    } catch (Throwable err) {
                        SdkLogger.debug("Spool replay failed for {}: {}", h.path(), err.getMessage());
                    }
                }
            } catch (Throwable err) {
                SdkLogger.debug("Spool drain failed: {}", err.getMessage());
            }
        }, "allstak-spool-drain");
        t.setDaemon(true);
        t.start();
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

    /**
     * The user id to stamp on the {@code /sessions/start} envelope, if a user
     * was set before {@code init}. Scope user wins over the legacy client-level
     * user. Fail-open: returns {@code null} on any error so init never breaks.
     */
    private String initialUserId() {
        try {
            UserContext user = Scopes.mergedForCapture().user();
            if (user == null) user = currentUser;
            return user != null ? user.getId() : null;
        } catch (Throwable t) {
            return null;
        }
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
            eventsCaptured.incrementAndGet();
            if (shutdown.get() || transport.isDisabled()) {
                eventsDropped.incrementAndGet();
                return;
            }

            // 1. sample_rate drop first — skip dropped events before any work.
            if (isSampledOut(config.getSampleRate())) {
                SdkLogger.debug("Exception dropped by sampleRate={}", config.getSampleRate());
                eventsDropped.incrementAndGet();
                return;
            }

            String exceptionClass = throwable.getClass().getName();
            String message = throwable.getMessage() != null ? throwable.getMessage() : exceptionClass;
            List<String> stackTrace = extractStackTrace(throwable);

            // Snapshot Global+Isolation+Current scopes once.
            MergedScope scope = Scopes.mergedForCapture();

            // Merge release-tracking tags (sdk.name/version, platform, dist,
            // commit.sha/branch) into metadata. Caller-supplied metadata wins,
            // then scope-supplied tags/contexts/extras layered on top so the
            // dashboard sees them as filterable fields. The event is masked
            // before beforeSend and again afterwards so hooks cannot leak PII.
            Map<String, Object> mergedMetaErr = new java.util.LinkedHashMap<>();
            for (var e : config.releaseTags().entrySet()) mergedMetaErr.put(e.getKey(), e.getValue());
            if (metadata != null) mergedMetaErr.putAll(metadata);
            if (!scope.tags().isEmpty()) mergedMetaErr.put("tags", scope.tags());
            if (!scope.contexts().isEmpty()) mergedMetaErr.put("contexts", scope.contexts());
            if (!scope.extras().isEmpty()) mergedMetaErr.put("extras", scope.extras());

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

            // Breadcrumbs from the merged scope. Falls back to the legacy
            // RingBuffer for callers still using the pre-Scopes API path.
            List<Breadcrumb> scopeCrumbs = scope.breadcrumbs();
            List<Breadcrumb> legacyCrumbs = breadcrumbBuffer.drain();
            List<Breadcrumb> eventBreadcrumbs;
            if (!scopeCrumbs.isEmpty() && !legacyCrumbs.isEmpty()) {
                eventBreadcrumbs = new java.util.ArrayList<>(scopeCrumbs.size() + legacyCrumbs.size());
                eventBreadcrumbs.addAll(scopeCrumbs);
                eventBreadcrumbs.addAll(legacyCrumbs);
            } else if (!scopeCrumbs.isEmpty()) {
                eventBreadcrumbs = scopeCrumbs;
            } else if (!legacyCrumbs.isEmpty()) {
                eventBreadcrumbs = legacyCrumbs;
            } else {
                eventBreadcrumbs = null;
            }

            // User: scope wins over the legacy client-level setUser. The
            // setUser facade has been routed to the scope so this is a no-op
            // for callers using the new API; the field stays for back-compat.
            UserContext user = scope.user() != null ? scope.user() : currentUser;
            // Privacy-by-default: drop email + ip when sendDefaultPii=false.
            user = redactUserForPii(user);

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

            // Release-health: attach the active session id so the backend's
            // error consumer can mark this session errored/crashed server-side.
            // Null when auto session tracking is disabled or no session is open.
            String sessionId = sessionTracker != null ? sessionTracker.currentSessionId() : null;

            ErrorEvent event = new ErrorEvent(
                    exceptionClass,
                    message,
                    stackTrace,
                    level != null ? level : (scope.level() != null ? scope.level() : "error"),
                    config.getEnvironment(),
                    config.getRelease(),
                    sessionId,
                    user,
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

            // 2. pre-hook masking + beforeSend — may modify or drop (null).
            // Fail-open sends the pre-masked event if the hook throws.
            ErrorEvent processed = applyBeforeSend(event);
            if (processed == null) {
                SdkLogger.debug("Exception dropped by beforeSend");
                eventsDropped.incrementAndGet();
                return;
            }

            // 3. final masking — scrub anything beforeSend reintroduced.
            ErrorEvent masked = withMaskedMetadata(processed, config.isSendDefaultPii());

            // 4. transport — errors are sent immediately, no buffering. If the
            // send is un-deliverable (offline / retries exhausted) the masked
            // payload is spooled so it survives a restart or outage.
            sendOrSpool(PATH_ERRORS, masked);

            // 5. release-health: bump the session to errored / crashed.
            if (sessionTracker != null) {
                String effectiveLevel = level != null ? level : "error";
                if ("fatal".equalsIgnoreCase(effectiveLevel)) {
                    sessionTracker.recordCrash();
                } else if ("error".equalsIgnoreCase(effectiveLevel)) {
                    sessionTracker.recordError();
                }
            }
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
            eventsCaptured.incrementAndGet();
            if (shutdown.get() || transport.isDisabled()) {
                eventsDropped.incrementAndGet();
                return;
            }

            if (!isValidLogLevel(level)) {
                SdkLogger.debug("Invalid log level '{}' — dropping log", level);
                eventsDropped.incrementAndGet();
                return;
            }

            // 1. sample_rate drop first.
            if (isSampledOut(config.getSampleRate())) {
                SdkLogger.debug("Log dropped by sampleRate={}", config.getSampleRate());
                eventsDropped.incrementAndGet();
                return;
            }

            if (config.isAutoBreadcrumbs() && ("warn".equals(level) || "error".equals(level) || "fatal".equals(level))) {
                breadcrumbBuffer.add(new Breadcrumb("log", message, level, metadata));
            }

            // Merge release-tracking tags into log metadata too. The event is
            // masked before beforeSend and again afterwards so hooks cannot leak PII.
            Map<String, Object> mergedMetaLog = new java.util.LinkedHashMap<>();
            for (var e : config.releaseTags().entrySet()) mergedMetaLog.put(e.getKey(), e.getValue());
            if (metadata != null) mergedMetaLog.putAll(metadata);
            String svc = service != null ? service : config.getServiceName();
            String env = environment != null ? environment : config.getEnvironment();

            LogEvent event = new LogEvent(level, message, svc, traceId, env, spanId,
                    requestId, userId, errorId, mergedMetaLog, config.getRelease());

            // 2. pre-hook masking + beforeSend — may modify or drop (null).
            // Fail-open sends the pre-masked event if the hook throws.
            LogEvent processed = applyBeforeSend(event);
            if (processed == null) {
                SdkLogger.debug("Log dropped by beforeSend");
                eventsDropped.incrementAndGet();
                return;
            }

            // 3. final masking — scrub anything beforeSend reintroduced.
            LogEvent masked = withMaskedMetadata(processed, config.isSendDefaultPii());

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
            eventsCaptured.incrementAndGet();
            if (shutdown.get() || transport.isDisabled()) {
                eventsDropped.incrementAndGet();
                return;
            }

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
            eventsCaptured.incrementAndGet();
            if (shutdown.get() || transport.isDisabled()) {
                eventsDropped.incrementAndGet();
                return;
            }

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
        Scopes.current().addBreadcrumb(new Breadcrumb(type, message, level, safeData));
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
        captureSpan(traceId, spanId, parentSpanId, operation, description, status,
                durationMs, startTimeMillis, endTimeMillis, service, environment, tags, null);
    }

    /**
     * Span capture with an optional free-form {@code data} bag (arbitrary
     * span data — arbitrary key/value context such as {@code db.system},
     * {@code http.status_code}). The legacy 12-arg overload keeps the
     * historical {@code data:""} wire shape so existing integrations and
     * their tests are unaffected; this overload emits {@code data} as a JSON
     * object only when non-empty. Honors the same {@code tracesSampleRate}
     * span-drop gate. Fail-open.
     */
    public void captureSpan(String traceId, String spanId, String parentSpanId,
                            String operation, String description, String status,
                            long durationMs, long startTimeMillis, long endTimeMillis,
                            String service, String environment, Map<String, String> tags,
                            Map<String, Object> data) {
        captureSpan(traceId, spanId, parentSpanId, operation, description, status,
                durationMs, startTimeMillis, endTimeMillis, service, environment, tags, data, false);
    }

    /**
     * Span capture where the caller has <b>already</b> made the sampling
     * decision ({@code preSampled=true} skips the internal
     * {@code tracesSampleRate} re-roll). The first-class
     * {@link dev.allstak.tracing.Transaction}/{@link dev.allstak.tracing.Span}
     * API uses this: the transaction decides once at start (via
     * {@link #isSpanSampled(dev.allstak.tracing.SamplingContext)}), children
     * inherit it, and only sampled spans ever reach {@code finish()} →
     * {@code captureSpan}. Re-rolling here would double-sample and could drop a
     * span the transaction already kept (or vice-versa). Auto-instrumentation
     * that has no transaction context keeps {@code preSampled=false} and lets
     * this method apply the gate.
     */
    public void captureSpan(String traceId, String spanId, String parentSpanId,
                            String operation, String description, String status,
                            long durationMs, long startTimeMillis, long endTimeMillis,
                            String service, String environment, Map<String, String> tags,
                            Map<String, Object> data, boolean preSampled) {
        eventsCaptured.incrementAndGet();
        if (shutdown.get() || transport.isDisabled()) {
            eventsDropped.incrementAndGet();
            return;
        }
        // Span sampling — when tracesSampleRate is configured, drop unsampled
        // spans. Null keeps the legacy always-on behavior. Skipped when the
        // caller already made the decision (manual transaction/span tree).
        if (!preSampled && !isSpanSampled()) {
            SdkLogger.debug("Span dropped by tracesSampleRate={}", config.getTracesSampleRate());
            eventsDropped.incrementAndGet();
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
            // Scrub PII from data values before they go on the wire, then emit
            // as a JSON object when present — otherwise keep the historical
            // empty-string default so the wire shape is unchanged for callers
            // that don't supply data.
            if (data != null && !data.isEmpty()) {
                span.put("data", DataMasker.maskMetadata(data, config.isSendDefaultPii()));
            } else {
                span.put("data", "");
            }

            Map<String, Object> payload = Map.of("spans", List.of(span));
            sendOrSpool(PATH_SPANS, payload);
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
            // Close the release-health session BEFORE the transport is torn
            // down so the /sessions/end POST has a chance to land. Status
            // is decided by what the session accumulated during its life.
            if (sessionTracker != null) {
                sessionTracker.end(null);
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

    /** Privacy-safe SDK diagnostics. Contains counters and queue sizes only. */
    public AllStakDiagnostics getDiagnostics() {
        TransportDiagnostics tx = transport.getDiagnostics();
        int bufferQueueSize = logBuffer.size() + httpBuffer.size() + dbQueryBuffer.size();
        int spoolQueueSize = spool != null ? spool.size() : 0;
        long bufferDrops = logBuffer.droppedCount()
                + httpBuffer.droppedCount()
                + dbQueryBuffer.droppedCount()
                + breadcrumbBuffer.droppedCount();
        int activeSpans = SpanScope.depth();
        return new AllStakDiagnostics(
                Math.max(eventsCaptured.get(), tx.getEventsCaptured()),
                tx.getEventsSent(),
                tx.getEventsFailed(),
                eventsDropped.get() + tx.getEventsDropped() + bufferDrops,
                eventsPersisted.get(),
                eventsReplayed.get(),
                bufferQueueSize + spoolQueueSize,
                tx.getRetryAttempts(),
                tx.getRateLimitedCount(),
                tx.getCompressedPayloads(),
                tx.getUncompressedPayloads(),
                tx.getCompressionBytesSaved(),
                DataMasker.redactionCount(),
                activeSpans > 0 ? 1 : 0,
                activeSpans,
                breadcrumbBuffer.size() + safeScopeBreadcrumbCount(),
                sessionTracker != null ? sessionTracker.recoveryCount() : 0,
                tx.isDisabled() || shutdown.get());
    }

    private static int safeScopeBreadcrumbCount() {
        try {
            return Scopes.mergedForCapture().breadcrumbs().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * The id of the active release-health session, or {@code null} when auto
     * session tracking is disabled or no session is open. Exposed so the
     * tracing API can correlate a transaction tree with its session.
     */
    public String currentSessionId() {
        return sessionTracker != null ? sessionTracker.currentSessionId() : null;
    }

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
        return isSpanSampled(null);
    }

    /**
     * Context-aware variant. Consult {@code tracesSampler} first (if any)
     * for a per-transaction probability, then fall back to the static
     * {@code tracesSampleRate}, then to the parent's sampled bit, then to
     * "always sampled" (legacy behavior).
     *
     * @param context per-transaction inputs; pass {@code null} for the
     *                no-context fallback (treated as {@code tracesSampleRate}).
     */
    public boolean isSpanSampled(dev.allstak.tracing.SamplingContext context) {
        dev.allstak.tracing.TracesSampler tsSampler = config.getTracesSampler();
        Double rate = null;
        if (tsSampler != null && context != null) {
            try {
                rate = tsSampler.sample(context);
            } catch (Throwable t) {
                SdkLogger.debug("tracesSampler threw — falling back: {}", t.getMessage());
            }
        }
        if (rate == null) rate = config.getTracesSampleRate();
        // Parent-sampled bit honors only when no explicit decision was set
        // at this hop, following "child inherits parent unless a local
        // override exists" semantics.
        if (rate == null && context != null && context.parentSampled() != null) {
            return context.parentSampled();
        }
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
        T sanitized = sanitizeForBeforeSend(event);
        try {
            Object result = hook.apply(sanitized);
            // null is a deliberate drop; any non-null is the (possibly modified) event.
            return (T) result;
        } catch (Throwable t) {
            // Fail-open: log and send the pre-sanitized event.
            SdkLogger.debug("beforeSend threw — sending sanitized event: {}", t.getMessage());
            return sanitized;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T sanitizeForBeforeSend(T event) {
        try {
            if (event instanceof ErrorEvent e) {
                return (T) withMaskedMetadata(e, config.isSendDefaultPii());
            }
            if (event instanceof LogEvent e) {
                return (T) withMaskedMetadata(e, config.isSendDefaultPii());
            }
            return event;
        } catch (Throwable t) {
            SdkLogger.debug("Pre-beforeSend sanitization failed — using redacted event: {}", t.getMessage());
            if (event instanceof ErrorEvent e) {
                return (T) redactedErrorEvent(e);
            }
            if (event instanceof LogEvent e) {
                return (T) redactedLogEvent(e);
            }
            return event;
        }
    }

    private static ErrorEvent redactedErrorEvent(ErrorEvent e) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("redacted", true);
        return new ErrorEvent(
                e.getExceptionClass(), "[REDACTED]", e.getStackTrace(), e.getLevel(),
                e.getEnvironment(), e.getRelease(), e.getSessionId(), e.getUser(),
                metadata, e.getTraceId(), e.getRequestContext(), e.getBreadcrumbs(),
                e.getPlatform(), e.getSdkName(), e.getSdkVersion(), e.getDist(), e.getFrames());
    }

    private static LogEvent redactedLogEvent(LogEvent e) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("redacted", true);
        return new LogEvent(e.getLevel(), "[REDACTED]", e.getService(), e.getTraceId(),
                e.getEnvironment(), e.getSpanId(), e.getRequestId(), e.getUserId(),
                e.getErrorId(), metadata, e.getRelease());
    }

    /**
     * Scrub PII from the error event: key-redaction + value-pattern scrubbing
     * on metadata values, and value-pattern scrubbing on the exception message
     * (the most common free-text PII leak). Stack frames, release/sdk fields,
     * the explicit user object, and request URLs/paths are intentionally left
     * untouched. Fail-open: returns the event with at most key-redaction on any
     * scrubber error so an event is never dropped here.
     */
    private static ErrorEvent withMaskedMetadata(ErrorEvent e, boolean sendDefaultPii) {
        try {
            Map<String, Object> masked = e.getMetadata() == null
                    ? null
                    : DataMasker.maskMetadata(e.getMetadata(), sendDefaultPii);
            String maskedMessage = DataMasker.scrubValue(e.getMessage(), sendDefaultPii);
            // The legacy v1 stackTrace string list echoes the exception message
            // in its header lines; scrub those the same way as the message.
            // Frame lines ("at class.method(File:line)") never match a PII
            // pattern, and the structured Frame objects (filename/function/
            // absPath) are intentionally left untouched.
            List<String> maskedStack = maskStackTrace(e.getStackTrace(), sendDefaultPii);
            List<Breadcrumb> maskedCrumbs = maskBreadcrumbs(e.getBreadcrumbs(), sendDefaultPii);
            if (masked == e.getMetadata()
                    && java.util.Objects.equals(maskedMessage, e.getMessage())
                    && maskedStack == e.getStackTrace()
                    && maskedCrumbs == e.getBreadcrumbs()) {
                return e;
            }
            return new ErrorEvent(
                    e.getExceptionClass(), maskedMessage, maskedStack, e.getLevel(),
                    e.getEnvironment(), e.getRelease(), e.getSessionId(), e.getUser(),
                    masked, e.getTraceId(), e.getRequestContext(), maskedCrumbs,
                    e.getPlatform(), e.getSdkName(), e.getSdkVersion(), e.getDist(), e.getFrames());
        } catch (Throwable t) {
            SdkLogger.debug("Value scrubbing failed for error — key-redaction only: {}", t.getMessage());
            if (e.getMetadata() == null) return e;
            return new ErrorEvent(
                    e.getExceptionClass(), e.getMessage(), e.getStackTrace(), e.getLevel(),
                    e.getEnvironment(), e.getRelease(), e.getSessionId(), e.getUser(),
                    DataMasker.maskMetadata(e.getMetadata()), e.getTraceId(), e.getRequestContext(),
                    e.getBreadcrumbs(), e.getPlatform(), e.getSdkName(), e.getSdkVersion(),
                    e.getDist(), e.getFrames());
        }
    }

    private static LogEvent withMaskedMetadata(LogEvent e, boolean sendDefaultPii) {
        try {
            Map<String, Object> masked = e.getMetadata() == null
                    ? null
                    : DataMasker.maskMetadata(e.getMetadata(), sendDefaultPii);
            String maskedMessage = DataMasker.scrubValue(e.getMessage(), sendDefaultPii);
            if (masked == e.getMetadata() && java.util.Objects.equals(maskedMessage, e.getMessage())) {
                return e;
            }
            return new LogEvent(e.getLevel(), maskedMessage, e.getService(), e.getTraceId(),
                    e.getEnvironment(), e.getSpanId(), e.getRequestId(), e.getUserId(),
                    e.getErrorId(), masked, e.getRelease());
        } catch (Throwable t) {
            SdkLogger.debug("Value scrubbing failed for log — key-redaction only: {}", t.getMessage());
            if (e.getMetadata() == null) return e;
            return new LogEvent(e.getLevel(), e.getMessage(), e.getService(), e.getTraceId(),
                    e.getEnvironment(), e.getSpanId(), e.getRequestId(), e.getUserId(),
                    e.getErrorId(), DataMasker.maskMetadata(e.getMetadata()), e.getRelease());
        }
    }

    /**
     * Scrub PII from the v1 stackTrace string list. Each entry is run through
     * the value scrubbers: exception-header lines carry the same free-text
     * message we already scrub, while {@code "at class.method(File:line)"}
     * frame lines never match a PII pattern and so pass through unchanged.
     * Returns the same list instance when nothing changed.
     */
    private static List<String> maskStackTrace(List<String> stack, boolean sendDefaultPii) {
        if (stack == null || stack.isEmpty()) return stack;
        List<String> out = null;
        for (int i = 0; i < stack.size(); i++) {
            String line = stack.get(i);
            String scrubbed = DataMasker.scrubValue(line, sendDefaultPii);
            if (!java.util.Objects.equals(scrubbed, line) && out == null) {
                out = new java.util.ArrayList<>(stack.subList(0, i));
            }
            if (out != null) out.add(scrubbed);
        }
        return out != null ? out : stack;
    }

    /**
     * Scrub PII from breadcrumb message + data while preserving type/category/
     * level/timestamp. Returns the same list instance when nothing changed so
     * callers can cheaply detect a no-op.
     */
    private static List<Breadcrumb> maskBreadcrumbs(List<Breadcrumb> crumbs, boolean sendDefaultPii) {
        if (crumbs == null || crumbs.isEmpty()) return crumbs;
        List<Breadcrumb> out = null;
        for (int i = 0; i < crumbs.size(); i++) {
            Breadcrumb b = crumbs.get(i);
            String scrubbedMsg = DataMasker.scrubValue(b.getMessage(), sendDefaultPii);
            Map<String, Object> scrubbedData = b.getData() == null
                    ? null
                    : DataMasker.maskMetadata(b.getData(), sendDefaultPii);
            boolean changed = !java.util.Objects.equals(scrubbedMsg, b.getMessage())
                    || scrubbedData != b.getData();
            if (changed && out == null) {
                out = new java.util.ArrayList<>(crumbs.subList(0, i));
            }
            if (out != null) {
                out.add(changed
                        ? new Breadcrumb(b.getType(), b.getCategory(), scrubbedMsg, b.getLevel(), scrubbedData)
                        : b);
            }
        }
        return out != null ? out : crumbs;
    }

    private static boolean isValidLogLevel(String level) {
        return level != null && switch (level) {
            case "debug", "info", "warn", "error", "fatal" -> true;
            default -> false;
        };
    }

    /**
     * Privacy-by-default user redaction. When {@code sendDefaultPii=false}
     * (the SDK default), strip {@code email} and {@code ip} from the
     * outgoing {@link UserContext} and keep only the stable {@code id}.
     *
     * <p>The principle: an opaque user id is the minimum
     * needed to compute distinct-users metrics; everything else is treated
     * as opt-in. A caller-supplied user with no id at all is dropped to
     * avoid shipping a pure-PII record.
     */
    /**
     * Post an end-user feedback record. The {@code eventId} ties the
     * feedback to an error previously captured via {@link #captureException}.
     */
    public void captureFeedback(dev.allstak.feedback.UserFeedback feedback) {
        if (shutdown.get() || transport.isDisabled() || feedback == null) return;
        try {
            transport.send(PATH_FEEDBACK, feedback);
        } catch (Exception e) {
            SdkLogger.debug("Failed to capture feedback: {}", e.getMessage());
        }
    }

    /**
     * Upload a binary attachment tied to a previously captured error event.
     * Posted as the existing {@code POST /ingest/v1/errors/{eventId}/attachments}
     * envelope (same shape as the screenshot-capture path used by the JS
     * SDK) so the dashboard renders it inline on the issue page.
     */
    public void captureAttachment(dev.allstak.feedback.Attachment attachment) {
        if (shutdown.get() || transport.isDisabled() || attachment == null) return;
        if (attachment.getEventId() == null || attachment.getEventId().isBlank()) {
            SdkLogger.debug("Attachment dropped — missing eventId");
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventId", attachment.getEventId());
            payload.put("kind", attachment.getKind() == null ? "attachment" : attachment.getKind());
            payload.put("contentType", attachment.getContentType());
            payload.put("dataBase64",
                    attachment.getBytes() == null ? "" : java.util.Base64.getEncoder().encodeToString(attachment.getBytes()));
            transport.send(PATH_ATTACHMENTS, payload);
        } catch (Exception e) {
            SdkLogger.debug("Failed to capture attachment: {}", e.getMessage());
        }
    }

    private UserContext redactUserForPii(UserContext user) {
        if (user == null) return null;
        if (config.isSendDefaultPii()) return user;
        if (user.getId() == null || user.getId().isBlank()) return null;
        if (user.getEmail() == null && user.getIp() == null) return user;
        return UserContext.ofId(user.getId());
    }
}
