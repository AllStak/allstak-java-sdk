package dev.allstak.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot configuration properties for AllStak SDK.
 * Configured via application.properties/yml under the "allstak" prefix.
 *
 * <p>Note: the ingest host is hardcoded inside the SDK and is intentionally
 * not configurable. Customers only need to provide their API key.
 *
 * <pre>
 * allstak.api-key=ask_live_xxx
 * allstak.environment=production
 * allstak.release=v1.0.0
 * allstak.debug=false
 * allstak.enabled=true
 * allstak.service-name=my-service
 * allstak.flush-interval-ms=5000
 * allstak.buffer-size=500
 * allstak.capture-http-requests=true
 * allstak.capture-exceptions=true
 * </pre>
 */
@ConfigurationProperties(prefix = "allstak")
public class AllStakProperties {

    private String apiKey;
    private String environment;
    private String release;
    private boolean debug = false;
    private boolean enabled = true;
    private String serviceName;
    private long flushIntervalMs = 5000;
    private int bufferSize = 500;
    private boolean captureHttpRequests = true;
    private boolean captureExceptions = true;
    private boolean captureDbQueries = true;
    private boolean captureLogs = true;
    private boolean captureScheduled = true;
    /** Build distribution tag (e.g. "linux-x86_64"). Optional. */
    private String dist;
    /** Git commit SHA (auto-detected from GIT_COMMIT / VERCEL_GIT_COMMIT_SHA when blank). */
    private String commitSha;
    /** Git branch (auto-detected from GIT_BRANCH / VERCEL_GIT_COMMIT_REF when blank). */
    private String branch;
    /** Install a global JVM uncaught-exception handler for background threads. Default true. */
    private boolean installUncaughtExceptionHandler = true;
    /** Error/message sampling rate in [0.0, 1.0]. Default 1.0 (keep everything). */
    private double sampleRate = 1.0;
    /** Span sampling rate in [0.0, 1.0]. Null (default) = always-on legacy behavior. */
    private Double tracesSampleRate;
    /** Phase A.3 — privacy-by-default. Strip user.email/ip + request bodies unless true. */
    private boolean sendDefaultPii = false;
    /** Phase A.2 — open one release-health session per JVM. */
    private boolean enableAutoSessionTracking = true;
    /** Phase A.4 — regex / substring allowlist for outbound trace-header injection. */
    private java.util.List<String> tracePropagationTargets;
    /** Phase E.2 — start a JFR-based continuous profiler at app boot. */
    private boolean enableProfiling = false;
    /** Phase B/C feature gates — let users disable individual integrations without
     *  removing the module from the classpath. Defaults follow Sentry's stance:
     *  on when the dependency is present. */
    private boolean captureOkHttp = true;
    private boolean captureApacheHttp = true;
    private boolean captureFeign = true;
    private boolean captureReactor = true;
    private boolean captureQuartz = true;
    private boolean captureKafka = true;
    private boolean captureSecurityUser = true;
    private boolean captureLettuce = true;
    private boolean captureGraphql = true;
    private boolean captureSpringCache = true;

    public boolean isInstallUncaughtExceptionHandler() { return installUncaughtExceptionHandler; }
    public void setInstallUncaughtExceptionHandler(boolean installUncaughtExceptionHandler) { this.installUncaughtExceptionHandler = installUncaughtExceptionHandler; }
    public double getSampleRate() { return sampleRate; }
    public void setSampleRate(double sampleRate) { this.sampleRate = sampleRate; }
    public Double getTracesSampleRate() { return tracesSampleRate; }
    public void setTracesSampleRate(Double tracesSampleRate) { this.tracesSampleRate = tracesSampleRate; }

    public String getDist() { return dist; }
    public void setDist(String dist) { this.dist = dist; }
    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getRelease() { return release; }
    public void setRelease(String release) { this.release = release; }
    public boolean isDebug() { return debug; }
    public void setDebug(boolean debug) { this.debug = debug; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public long getFlushIntervalMs() { return flushIntervalMs; }
    public void setFlushIntervalMs(long flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; }
    public int getBufferSize() { return bufferSize; }
    public void setBufferSize(int bufferSize) { this.bufferSize = bufferSize; }
    public boolean isCaptureHttpRequests() { return captureHttpRequests; }
    public void setCaptureHttpRequests(boolean captureHttpRequests) { this.captureHttpRequests = captureHttpRequests; }
    public boolean isCaptureExceptions() { return captureExceptions; }
    public void setCaptureExceptions(boolean captureExceptions) { this.captureExceptions = captureExceptions; }
    public boolean isCaptureDbQueries() { return captureDbQueries; }
    public void setCaptureDbQueries(boolean captureDbQueries) { this.captureDbQueries = captureDbQueries; }
    public boolean isCaptureLogs() { return captureLogs; }
    public void setCaptureLogs(boolean captureLogs) { this.captureLogs = captureLogs; }
    public boolean isCaptureScheduled() { return captureScheduled; }
    public void setCaptureScheduled(boolean captureScheduled) { this.captureScheduled = captureScheduled; }

    public boolean isSendDefaultPii() { return sendDefaultPii; }
    public void setSendDefaultPii(boolean sendDefaultPii) { this.sendDefaultPii = sendDefaultPii; }
    public boolean isEnableAutoSessionTracking() { return enableAutoSessionTracking; }
    public void setEnableAutoSessionTracking(boolean v) { this.enableAutoSessionTracking = v; }
    public java.util.List<String> getTracePropagationTargets() { return tracePropagationTargets; }
    public void setTracePropagationTargets(java.util.List<String> tracePropagationTargets) { this.tracePropagationTargets = tracePropagationTargets; }
    public boolean isEnableProfiling() { return enableProfiling; }
    public void setEnableProfiling(boolean enableProfiling) { this.enableProfiling = enableProfiling; }

    public boolean isCaptureOkHttp() { return captureOkHttp; }
    public void setCaptureOkHttp(boolean v) { this.captureOkHttp = v; }
    public boolean isCaptureApacheHttp() { return captureApacheHttp; }
    public void setCaptureApacheHttp(boolean v) { this.captureApacheHttp = v; }
    public boolean isCaptureFeign() { return captureFeign; }
    public void setCaptureFeign(boolean v) { this.captureFeign = v; }
    public boolean isCaptureReactor() { return captureReactor; }
    public void setCaptureReactor(boolean v) { this.captureReactor = v; }
    public boolean isCaptureQuartz() { return captureQuartz; }
    public void setCaptureQuartz(boolean v) { this.captureQuartz = v; }
    public boolean isCaptureKafka() { return captureKafka; }
    public void setCaptureKafka(boolean v) { this.captureKafka = v; }
    public boolean isCaptureSecurityUser() { return captureSecurityUser; }
    public void setCaptureSecurityUser(boolean v) { this.captureSecurityUser = v; }
    public boolean isCaptureLettuce() { return captureLettuce; }
    public void setCaptureLettuce(boolean v) { this.captureLettuce = v; }
    public boolean isCaptureGraphql() { return captureGraphql; }
    public void setCaptureGraphql(boolean v) { this.captureGraphql = v; }
    public boolean isCaptureSpringCache() { return captureSpringCache; }
    public void setCaptureSpringCache(boolean v) { this.captureSpringCache = v; }
}
