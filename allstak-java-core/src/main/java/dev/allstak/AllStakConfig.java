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
    public static final String SDK_VERSION = "0.1.5";

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
    // Global uncaught-exception capture (background / non-web threads).
    private final boolean installUncaughtExceptionHandler;
    // Event hooks & sampling.
    private final Function<Object, Object> beforeSend;
    private final double sampleRate;
    private final Double tracesSampleRate;

    private AllStakConfig(Builder builder) {
        this.apiKey = builder.apiKey;
        this.environment = builder.environment != null ? builder.environment : envOr("ALLSTAK_ENVIRONMENT", "production");
        this.release = builder.release != null ? builder.release : envOrNull("ALLSTAK_RELEASE", "VERCEL_GIT_COMMIT_SHA", "RAILWAY_GIT_COMMIT_SHA", "RENDER_GIT_COMMIT");
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
        this.installUncaughtExceptionHandler = builder.installUncaughtExceptionHandler;
        this.beforeSend = builder.beforeSend;
        this.sampleRate = builder.sampleRate;
        this.tracesSampleRate = builder.tracesSampleRate;
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
    /** Whether to install a global {@code Thread.setDefaultUncaughtExceptionHandler}. Default true. */
    public boolean isInstallUncaughtExceptionHandler() { return installUncaughtExceptionHandler; }
    /**
     * Optional callback invoked once just before an event is sent to transport.
     * Receives the SDK event ({@link dev.allstak.model.ErrorEvent} or
     * {@link dev.allstak.model.LogEvent}) and may return a modified event, the
     * same event, or {@code null} to drop it. Runs for both exception and
     * message capture, after sample-rate filtering and before PII masking.
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
        private boolean installUncaughtExceptionHandler = true;
        private Function<Object, Object> beforeSend;
        private double sampleRate = 1.0;
        private Double tracesSampleRate;

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
        /** Opt out of (or back into) the global uncaught-exception handler install. Default true. */
        public Builder installUncaughtExceptionHandler(boolean install) { this.installUncaughtExceptionHandler = install; return this; }
        /**
         * Callback invoked just before an event is sent. Return the event
         * (modified or not) to keep it, or {@code null} to drop it. Receives an
         * {@link dev.allstak.model.ErrorEvent} or {@link dev.allstak.model.LogEvent}.
         */
        public Builder beforeSend(Function<Object, Object> beforeSend) { this.beforeSend = beforeSend; return this; }
        /** Error/message sampling rate in [0.0, 1.0]. Default 1.0. */
        public Builder sampleRate(double sampleRate) { this.sampleRate = sampleRate; return this; }
        /** Span sampling rate in [0.0, 1.0], or {@code null} (default) for always-on. */
        public Builder tracesSampleRate(Double tracesSampleRate) { this.tracesSampleRate = tracesSampleRate; return this; }

        public AllStakConfig build() {
            AllStakConfig config = new AllStakConfig(this);
            config.validate();
            return config;
        }
    }
}
