package dev.allstak;

import java.util.Objects;
import java.util.Set;

/**
 * Configuration for the AllStak SDK. Use the {@link Builder} to construct.
 *
 * <p>The ingest host defaults to {@link #INGEST_HOST}. Self-hosted and dev
 * deployments can override it with {@link Builder#host(String)} or
 * {@code ALLSTAK_HOST}.
 */
public final class AllStakConfig {

    /**
     * The single, static AllStak ingest host. Not customer-configurable on purpose:
     * customers should never have to know or care about which URL their events go to.
     */
    public static final String INGEST_HOST = "https://api.allstak.sa";
    /** Hardcoded SDK identity. Sent on the wire as {@code sdk.name} / {@code sdk.version}. */
    public static final String SDK_NAME = "allstak-java";
    public static final String SDK_VERSION = "0.1.1";

    private final String apiKey;
    private final String host;
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
    private final BodyCaptureConfig httpBodyCapture;

    private AllStakConfig(Builder builder) {
        this.apiKey = builder.apiKey;
        this.host = normalizeHost(builder.host != null ? builder.host : envOr("ALLSTAK_HOST", INGEST_HOST));
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
        this.httpBodyCapture = builder.httpBodyCapture != null
                ? builder.httpBodyCapture
                : BodyCaptureConfig.fromEnvironment();
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
    /** Returns the configured ingest host. Defaults to {@link #INGEST_HOST}. */
    public String getHost() { return host; }
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
    public BodyCaptureConfig getHttpBodyCapture() { return httpBodyCapture; }

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
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String apiKey;
        private String environment;
        private String host;
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
        private BodyCaptureConfig httpBodyCapture;

        private Builder() {}

        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder host(String host) { this.host = host; return this; }
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
        public Builder httpBodyCapture(BodyCaptureConfig httpBodyCapture) { this.httpBodyCapture = httpBodyCapture; return this; }
        public Builder httpBodyCaptureEnabled(boolean enabled) {
            this.httpBodyCapture = BodyCaptureConfig.builder().enabled(enabled).build();
            return this;
        }

        public AllStakConfig build() {
            AllStakConfig config = new AllStakConfig(this);
            config.validate();
            return config;
        }
    }

    private static String normalizeHost(String raw) {
        if (raw == null || raw.isBlank()) return INGEST_HOST;
        String trimmed = raw.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public static final class BodyCaptureConfig {
        private final boolean enabled;
        private final int maxBodySizeBytes;
        private final Set<String> contentTypes;
        private final Set<String> allowedRoutes;
        private final Set<String> deniedRoutes;
        private final Set<String> redactedFields;

        private BodyCaptureConfig(BodyCaptureBuilder builder) {
            this.enabled = builder.enabled;
            this.maxBodySizeBytes = builder.maxBodySizeBytes;
            this.contentTypes = Set.copyOf(builder.contentTypes);
            this.allowedRoutes = Set.copyOf(builder.allowedRoutes);
            this.deniedRoutes = Set.copyOf(builder.deniedRoutes);
            this.redactedFields = Set.copyOf(builder.redactedFields);
        }

        public boolean isEnabled() { return enabled; }
        public int getMaxBodySizeBytes() { return maxBodySizeBytes; }
        public Set<String> getContentTypes() { return contentTypes; }
        public Set<String> getAllowedRoutes() { return allowedRoutes; }
        public Set<String> getDeniedRoutes() { return deniedRoutes; }
        public Set<String> getRedactedFields() { return redactedFields; }

        public static BodyCaptureBuilder builder() { return new BodyCaptureBuilder(); }

        private static BodyCaptureConfig fromEnvironment() {
            return builder()
                    .enabled(Boolean.parseBoolean(envOr("ALLSTAK_HTTP_BODY_CAPTURE_ENABLED", "false")))
                    .maxBodySizeBytes(parseInt(envOr("ALLSTAK_HTTP_BODY_CAPTURE_MAX_BYTES", "8192"), 8192))
                    .contentTypes(csvSet(envOr("ALLSTAK_HTTP_BODY_CAPTURE_CONTENT_TYPES", "application/json,text/plain,application/x-www-form-urlencoded")))
                    .allowedRoutes(csvSet(envOr("ALLSTAK_HTTP_BODY_CAPTURE_ALLOW_ROUTES", "")))
                    .deniedRoutes(csvSet(envOr("ALLSTAK_HTTP_BODY_CAPTURE_DENY_ROUTES", "")))
                    .redactedFields(csvSet(envOr("ALLSTAK_HTTP_BODY_CAPTURE_REDACT_FIELDS", "")))
                    .build();
        }

        private static int parseInt(String raw, int fallback) {
            try { return Integer.parseInt(raw); } catch (Exception e) { return fallback; }
        }

        private static Set<String> csvSet(String raw) {
            if (raw == null || raw.isBlank()) return Set.of();
            return java.util.Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    public static final class BodyCaptureBuilder {
        private boolean enabled = false;
        private int maxBodySizeBytes = 8192;
        private Set<String> contentTypes = Set.of("application/json", "text/plain", "application/x-www-form-urlencoded");
        private Set<String> allowedRoutes = Set.of();
        private Set<String> deniedRoutes = Set.of();
        private Set<String> redactedFields = Set.of();

        private BodyCaptureBuilder() {}

        public BodyCaptureBuilder enabled(boolean enabled) { this.enabled = enabled; return this; }
        public BodyCaptureBuilder maxBodySizeBytes(int maxBodySizeBytes) { this.maxBodySizeBytes = Math.max(0, maxBodySizeBytes); return this; }
        public BodyCaptureBuilder contentTypes(Set<String> contentTypes) { this.contentTypes = contentTypes != null ? contentTypes : Set.of(); return this; }
        public BodyCaptureBuilder allowedRoutes(Set<String> allowedRoutes) { this.allowedRoutes = allowedRoutes != null ? allowedRoutes : Set.of(); return this; }
        public BodyCaptureBuilder deniedRoutes(Set<String> deniedRoutes) { this.deniedRoutes = deniedRoutes != null ? deniedRoutes : Set.of(); return this; }
        public BodyCaptureBuilder redactedFields(Set<String> redactedFields) { this.redactedFields = redactedFields != null ? redactedFields : Set.of(); return this; }
        public BodyCaptureConfig build() { return new BodyCaptureConfig(this); }
    }
}
