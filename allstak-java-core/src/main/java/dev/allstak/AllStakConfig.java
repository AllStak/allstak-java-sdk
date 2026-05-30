package dev.allstak;

import java.util.Objects;
import java.util.function.Function;

/**
 * Configuration for the AllStak SDK. Use the {@link Builder} to construct.
 *
 * <p>The ingest host defaults to {@link #INGEST_HOST}. Local validation and
 * self-hosted deployments can override it with {@code ALLSTAK_HOST} without
 * adding another required setup option for normal production users.
 */
public final class AllStakConfig {

    /**
     * The default AllStak ingest host. Customers should not need to set this
     * for standard production usage.
     */
    public static final String INGEST_HOST = "https://api.allstak.sa";
    /** Hardcoded SDK identity. Sent on the wire as {@code sdk.name} / {@code sdk.version}. */
    public static final String SDK_NAME = "allstak-java";
    public static final String SDK_VERSION = "0.4.0";

    private final String apiKey;
    private final String environment;
    private final String release;
    private final long flushIntervalMs;
    private final int bufferSize;
    private final boolean debug;
    private final String serviceName;
    private final boolean autoBreadcrumbs;
    private final int maxBreadcrumbs;
    // Release-tracking metadata (auto-detected from env when left null).
    private final String dist;
    private final String commitSha;
    private final String branch;
    private final String platform;
    private final String sdkName;
    private final String sdkVersion;
    private final boolean autoRegisterRelease;
    // Global uncaught-exception capture (background / non-web threads).
    private final boolean installUncaughtExceptionHandler;
    // Event hooks & sampling.
    private final Function<Object, Object> beforeSend;
    private final double sampleRate;
    private final Double tracesSampleRate;
    // Release-health sessions.
    private final boolean enableAutoSessionTracking;
    // PII handling.
    private final boolean sendDefaultPii;
    // Per-transaction sampling + outbound trace-header allowlist.
    private final dev.allstak.tracing.TracesSampler tracesSampler;
    private final dev.allstak.tracing.TracePropagationDecider tracePropagationDecider;
    // Offline / persistent event queue (survive restart + outage).
    private final boolean enableOfflineQueue;
    private final String offlineQueueDir;
    private final int offlineQueueMaxEntries;
    private final long offlineQueueMaxBytes;
    private final long offlineQueueMaxAgeMs;

    private AllStakConfig(Builder builder) {
        this.apiKey = builder.apiKey;
        this.environment = builder.environment != null ? builder.environment : envOr("ALLSTAK_ENVIRONMENT", "production");
        // Release resolution, highest precedence first:
        //   1. explicit builder.release        (always wins)
        //   2. conventional CI env vars
        //   3. automatic git-describe detection (CI-free, gated by autoDetectRelease)
        //   4. SDK_VERSION fallback             (so release is never empty when detection is on)
        this.release = resolveRelease(builder);
        this.flushIntervalMs = builder.flushIntervalMs;
        this.bufferSize = builder.bufferSize;
        this.debug = builder.debug;
        this.serviceName = builder.serviceName;
        this.autoBreadcrumbs = builder.autoBreadcrumbs;
        this.maxBreadcrumbs = builder.maxBreadcrumbs;
        this.dist = builder.dist;
        this.commitSha = builder.commitSha != null ? builder.commitSha : envOrNull("ALLSTAK_COMMIT_SHA", "GIT_COMMIT", "VERCEL_GIT_COMMIT_SHA", "RAILWAY_GIT_COMMIT_SHA", "RENDER_GIT_COMMIT");
        this.branch = builder.branch != null ? builder.branch : envOrNull("ALLSTAK_BRANCH", "GIT_BRANCH", "VERCEL_GIT_COMMIT_REF", "RAILWAY_GIT_BRANCH");
        this.platform = builder.platform != null ? builder.platform : "jvm";
        this.sdkName = builder.sdkName != null ? builder.sdkName : SDK_NAME;
        this.sdkVersion = builder.sdkVersion != null ? builder.sdkVersion : SDK_VERSION;
        this.autoRegisterRelease = builder.autoRegisterRelease;
        this.installUncaughtExceptionHandler = builder.installUncaughtExceptionHandler;
        this.beforeSend = builder.beforeSend;
        this.sampleRate = builder.sampleRate;
        this.tracesSampleRate = builder.tracesSampleRate;
        this.enableAutoSessionTracking = builder.enableAutoSessionTracking;
        this.sendDefaultPii = builder.sendDefaultPii;
        this.tracesSampler = builder.tracesSampler;
        this.tracePropagationDecider = new dev.allstak.tracing.TracePropagationDecider(builder.tracePropagationTargets);
        this.enableOfflineQueue = builder.enableOfflineQueue;
        this.offlineQueueDir = builder.offlineQueueDir;
        this.offlineQueueMaxEntries = builder.offlineQueueMaxEntries;
        this.offlineQueueMaxBytes = builder.offlineQueueMaxBytes;
        this.offlineQueueMaxAgeMs = builder.offlineQueueMaxAgeMs;
    }

    private static String resolveRelease(Builder builder) {
        return resolveRelease(builder.release, builder.autoDetectRelease, ReleaseDetector::detectCached);
    }

    /**
     * Pure, seamable release resolution. Order, highest precedence first:
     * explicit value, CI env vars, automatic detection (the {@code detected}
     * supplier), then the {@link #SDK_VERSION} constant. Steps 3 and 4 only run
     * when {@code autoDetect} is true. The {@code detected} supplier is the
     * seam: tests pass a fake instead of the real git shell-out.
     */
    static String resolveRelease(String explicit, boolean autoDetect, java.util.function.Supplier<String> detected) {
        if (explicit != null && !explicit.isEmpty()) return explicit;
        String env = envOrNull("ALLSTAK_RELEASE", "VERCEL_GIT_COMMIT_SHA", "RAILWAY_GIT_COMMIT_SHA", "RENDER_GIT_COMMIT");
        if (env != null) return env;
        if (!autoDetect) return null;
        String auto = detected != null ? detected.get() : null;
        if (auto != null && !auto.isEmpty()) return auto;
        return SDK_VERSION;
    }

    private static String envOr(String key, String def) {
        try { String v = System.getenv(key); return (v != null && !v.isEmpty()) ? v : def; } catch (Exception e) { return def; }
    }
    private static String envOrNull(String... keys) {
        for (String k : keys) {
            try { String v = System.getenv(k); if (v != null && !v.isEmpty()) return v; } catch (Exception ignore) {}
        }
        return null;
    }

    public String getApiKey() { return apiKey; }
    /** Returns the effective ingest host. Defaults to {@link #INGEST_HOST}. */
    public String getHost() {
        return envOr("ALLSTAK_HOST", INGEST_HOST).replaceAll("/+$", "");
    }
    public String getEnvironment() { return environment; }
    public String getRelease() { return release; }
    public long getFlushIntervalMs() { return flushIntervalMs; }
    public int getBufferSize() { return bufferSize; }
    public boolean isDebug() { return debug; }
    public String getServiceName() { return serviceName; }
    public boolean isAutoBreadcrumbs() { return autoBreadcrumbs; }
    public int getMaxBreadcrumbs() { return maxBreadcrumbs; }
    public String getDist() { return dist; }
    public String getCommitSha() { return commitSha; }
    public String getBranch() { return branch; }
    public String getPlatform() { return platform; }
    public String getSdkName() { return sdkName; }
    public String getSdkVersion() { return sdkVersion; }
    public boolean isAutoRegisterRelease() { return autoRegisterRelease; }
    /** Whether to install a global {@code Thread.setDefaultUncaughtExceptionHandler}. Default true. */
    public boolean isInstallUncaughtExceptionHandler() { return installUncaughtExceptionHandler; }
    /**
     * Optional callback invoked once just before an event is sent to transport.
     * Receives the SDK event ({@link dev.allstak.model.ErrorEvent} or
     * {@link dev.allstak.model.LogEvent}) and may return a modified event, the
     * same event, or {@code null} to drop it. Runs for both exception and
     * message capture after sample-rate filtering and after a first PII masking
     * pass. The returned event is masked again before persistence/network send,
     * so hooks cannot reintroduce secrets.
     */
    public Function<Object, Object> getBeforeSend() { return beforeSend; }
    /** Error/message sampling rate in [0.0, 1.0]. Default 1.0 (keep everything). */
    public double getSampleRate() { return sampleRate; }
    /**
     * Optional span sampling rate in [0.0, 1.0]. When {@code null} (default)
     * spans are always created/sampled (legacy behavior). When set, drives the
     * {@code traceparent} sampled flag ({@code -01} sampled, {@code -00} not).
     */
    public Double getTracesSampleRate() { return tracesSampleRate; }
    /**
     * Whether the SDK should automatically start a release-health session on
     * {@code AllStak.init} and end it on {@code AllStak.shutdown}. Default
     * {@code true} for server-mode (one session per JVM). Disable for
     * environments without a release identifier or where you want to
     * manage sessions manually.
     */
    public boolean isEnableAutoSessionTracking() { return enableAutoSessionTracking; }
    /**
     * Whether to ship personally-identifying information by default.
     *
     * <p><b>Default {@code false}</b> — a privacy-by-default
     * stance. When false the SDK strips {@code user.email} and {@code user.ip}
     * on captured events, drops {@code Authorization} / {@code Cookie} headers
     * on captured HTTP requests, and skips request/response body capture in
     * the Servlet filter. Opt in only after auditing your {@code beforeSend}
     * scrubbing path.
     */
    public boolean isSendDefaultPii() { return sendDefaultPii; }
    /**
     * Optional per-transaction sampler. When set, takes precedence over
     * {@link #getTracesSampleRate()} for the transaction-start decision.
     * Returns {@code null} ⇒ defer to the static rate.
     */
    public dev.allstak.tracing.TracesSampler getTracesSampler() { return tracesSampler; }
    /**
     * Outbound trace-propagation allowlist. The SDK only injects trace
     * headers on outbound HTTP requests whose URL/host matches at least
     * one of the configured patterns; default is "propagate everywhere".
     * Use to keep your trace context from leaking to third-party domains.
     */
    public dev.allstak.tracing.TracePropagationDecider getTracePropagationDecider() { return tracePropagationDecider; }

    /**
     * Whether to persist un-sent telemetry to a filesystem spool so buffered
     * events survive a process restart or a network outage, replaying on the
     * next {@code init}. <b>Default {@code true}.</b> Session lifecycle calls
     * ({@code /sessions/start|end}) are never persisted. Degrades silently to
     * the existing in-memory behavior when the spool directory is not writable
     * (read-only FS, serverless, sandbox). Disable to keep telemetry purely
     * in-memory.
     */
    public boolean isEnableOfflineQueue() { return enableOfflineQueue; }
    /**
     * Directory for the offline spool. {@code null} (default) uses
     * {@code ${java.io.tmpdir}/allstak-spool/<apiKeyHash>}. Set to isolate or
     * relocate the store (e.g. a durable volume).
     */
    public String getOfflineQueueDir() { return offlineQueueDir; }
    /** Max persisted envelopes before the oldest are evicted. Default 200. */
    public int getOfflineQueueMaxEntries() { return offlineQueueMaxEntries; }
    /** Max total spool bytes before the oldest are evicted. Default ~5 MB. */
    public long getOfflineQueueMaxBytes() { return offlineQueueMaxBytes; }
    /** Max age before a persisted envelope is pruned as stale. Default 48h. */
    public long getOfflineQueueMaxAgeMs() { return offlineQueueMaxAgeMs; }

    /**
     * Release-tracking tags merged into every event payload's metadata so
     * the dashboard can group / filter by SDK / platform / commit / branch.
     * Backend reads these into dedicated columns in a future migration; for
     * now they ride inside the metadata JSON.
     */
    public java.util.Map<String, String> releaseTags() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (sdkName != null) out.put("sdk.name", sdkName);
        if (sdkVersion != null) out.put("sdk.version", sdkVersion);
        if (platform != null) out.put("platform", platform);
        if (dist != null) out.put("dist", dist);
        if (commitSha != null) out.put("commit.sha", commitSha);
        if (branch != null) out.put("commit.branch", branch);
        return out;
    }

    public void validate() {
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive");
        }
        if (flushIntervalMs <= 0) {
            throw new IllegalArgumentException("flushIntervalMs must be positive");
        }
        if (sampleRate < 0.0 || sampleRate > 1.0) {
            throw new IllegalArgumentException("sampleRate must be between 0.0 and 1.0");
        }
        if (tracesSampleRate != null && (tracesSampleRate < 0.0 || tracesSampleRate > 1.0)) {
            throw new IllegalArgumentException("tracesSampleRate must be between 0.0 and 1.0");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String apiKey;
        private String environment;
        private String release;
        private long flushIntervalMs = 5000;
        private int bufferSize = 500;
        private boolean debug = false;
        private String serviceName;
        private boolean autoBreadcrumbs = true;
        private int maxBreadcrumbs = 50;
        private String dist;
        private String commitSha;
        private String branch;
        private String platform;
        private String sdkName;
        private String sdkVersion;
        private boolean autoRegisterRelease = true;
        private boolean installUncaughtExceptionHandler = true;
        private boolean autoDetectRelease = true;
        private Function<Object, Object> beforeSend;
        private double sampleRate = 1.0;
        private Double tracesSampleRate;
        private boolean enableAutoSessionTracking = true;
        private boolean sendDefaultPii = false;
        private dev.allstak.tracing.TracesSampler tracesSampler;
        private java.util.List<String> tracePropagationTargets;
        private boolean enableOfflineQueue = true;
        private String offlineQueueDir;
        private int offlineQueueMaxEntries = dev.allstak.spool.EventSpool.DEFAULT_MAX_ENTRIES;
        private long offlineQueueMaxBytes = dev.allstak.spool.EventSpool.DEFAULT_MAX_BYTES;
        private long offlineQueueMaxAgeMs = dev.allstak.spool.EventSpool.DEFAULT_MAX_AGE_MS;

        private Builder() {}

        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder environment(String environment) { this.environment = environment; return this; }
        public Builder release(String release) { this.release = release; return this; }
        public Builder flushIntervalMs(long flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; return this; }
        public Builder bufferSize(int bufferSize) { this.bufferSize = bufferSize; return this; }
        public Builder debug(boolean debug) { this.debug = debug; return this; }
        public Builder serviceName(String serviceName) { this.serviceName = serviceName; return this; }
        public Builder autoBreadcrumbs(boolean autoBreadcrumbs) { this.autoBreadcrumbs = autoBreadcrumbs; return this; }
        public Builder maxBreadcrumbs(int maxBreadcrumbs) { this.maxBreadcrumbs = maxBreadcrumbs; return this; }
        public Builder dist(String dist) { this.dist = dist; return this; }
        public Builder commitSha(String commitSha) { this.commitSha = commitSha; return this; }
        public Builder branch(String branch) { this.branch = branch; return this; }
        public Builder platform(String platform) { this.platform = platform; return this; }
        public Builder sdkName(String sdkName) { this.sdkName = sdkName; return this; }
        public Builder sdkVersion(String sdkVersion) { this.sdkVersion = sdkVersion; return this; }
        /** Register the resolved release at SDK startup. Default true. */
        public Builder autoRegisterRelease(boolean autoRegisterRelease) { this.autoRegisterRelease = autoRegisterRelease; return this; }
        /** Opt out of (or back into) the global uncaught-exception handler install. Default true. */
        public Builder installUncaughtExceptionHandler(boolean install) { this.installUncaughtExceptionHandler = install; return this; }
        /**
         * Gate automatic, CI-free release detection and the SDK-version
         * fallback (steps 3 and 4 of release resolution). Default {@code true}.
         * When {@code false}, release stays null unless an explicit value or a
         * conventional CI env var provided one.
         */
        public Builder autoDetectRelease(boolean autoDetectRelease) { this.autoDetectRelease = autoDetectRelease; return this; }
        /**
         * Callback invoked just before an event is sent. Receives a sanitized
         * {@link dev.allstak.model.ErrorEvent} or {@link dev.allstak.model.LogEvent}.
         * Return the event (modified or not) to keep it, or {@code null} to drop it.
         * The returned event is sanitized again before persistence/network send.
         */
        public Builder beforeSend(Function<Object, Object> beforeSend) { this.beforeSend = beforeSend; return this; }
        /** Error/message sampling rate in [0.0, 1.0]. Default 1.0. */
        public Builder sampleRate(double sampleRate) { this.sampleRate = sampleRate; return this; }
        /** Span sampling rate in [0.0, 1.0], or {@code null} (default) for always-on. */
        public Builder tracesSampleRate(Double tracesSampleRate) { this.tracesSampleRate = tracesSampleRate; return this; }
        /**
         * Toggle automatic release-health session tracking. Default {@code true}.
         * When enabled, the SDK opens one session per JVM at {@code init} and
         * closes it on {@code shutdown}, marking it errored/crashed if events
         * of that severity were captured.
         */
        public Builder enableAutoSessionTracking(boolean enable) { this.enableAutoSessionTracking = enable; return this; }
        /**
         * Opt in to shipping user.email, user.ip, request bodies, and
         * Authorization/Cookie headers. Default {@code false}. Strongly
         * recommend pairing with a {@code beforeSend} hook that further
         * scrubs domain-specific PII before flipping this on.
         */
        public Builder sendDefaultPii(boolean sendDefaultPii) { this.sendDefaultPii = sendDefaultPii; return this; }
        /**
         * Per-transaction sampler. Receives a {@link dev.allstak.tracing.SamplingContext}
         * built from the inbound request and returns a probability in
         * {@code [0.0, 1.0]} or {@code null} to defer to {@link #tracesSampleRate(Double)}.
         */
        public Builder tracesSampler(dev.allstak.tracing.TracesSampler sampler) {
            this.tracesSampler = sampler; return this;
        }
        /**
         * Regex or substring patterns listing the outbound HTTP targets that
         * the SDK is allowed to inject trace headers on. {@code null} or
         * empty ⇒ propagate everywhere (default). Patterns are case-insensitive.
         */
        public Builder tracePropagationTargets(java.util.List<String> targets) {
            this.tracePropagationTargets = targets; return this;
        }
        /**
         * Toggle the offline / persistent event queue. Default {@code true}.
         * When on, telemetry that cannot be delivered (network down, retries
         * exhausted, or events still buffered at shutdown) is written — already
         * PII-scrubbed — to a filesystem spool and replayed on the next
         * {@code init}. Session lifecycle calls are excluded. Set {@code false}
         * to keep telemetry purely in-memory.
         */
        public Builder enableOfflineQueue(boolean enable) { this.enableOfflineQueue = enable; return this; }
        /**
         * Directory for the offline spool. {@code null} (default) uses
         * {@code ${java.io.tmpdir}/allstak-spool/<apiKeyHash>}.
         */
        public Builder offlineQueueDir(String dir) { this.offlineQueueDir = dir; return this; }
        /** Cap the spool by entry count (drop-oldest when exceeded). Default 200. */
        public Builder offlineQueueMaxEntries(int maxEntries) { this.offlineQueueMaxEntries = maxEntries; return this; }
        /** Cap the spool by total bytes (drop-oldest when exceeded). Default ~5 MB. */
        public Builder offlineQueueMaxBytes(long maxBytes) { this.offlineQueueMaxBytes = maxBytes; return this; }
        /** Max age before a persisted envelope is pruned as stale. Default 48h. */
        public Builder offlineQueueMaxAgeMs(long maxAgeMs) { this.offlineQueueMaxAgeMs = maxAgeMs; return this; }

        public AllStakConfig build() {
            AllStakConfig config = new AllStakConfig(this);
            config.validate();
            return config;
        }
    }
}
