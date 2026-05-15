package dev.allstak.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot configuration properties for AllStak SDK.
 * Configured via application.properties/yml under the "allstak" prefix.
 *
 * <pre>
 * allstak.api-key=ask_live_xxx
 * allstak.host=https://api.dev.allstak.sa
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
    private String host;
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
    private boolean captureAsync = true;
    private boolean captureRabbit = true;
    private boolean captureKafka = true;
    private boolean captureCache = true;
    private boolean captureFeign = true;
    private boolean captureValidation = true;
    private boolean captureSecurity = true;
    private boolean captureRetry = true;
    /** Build distribution tag (e.g. "linux-x86_64"). Optional. */
    private String dist;
    /** Git commit SHA (auto-detected from GIT_COMMIT / VERCEL_GIT_COMMIT_SHA when blank). */
    private String commitSha;
    /** Git branch (auto-detected from GIT_BRANCH / VERCEL_GIT_COMMIT_REF when blank). */
    private String branch;

    public String getDist() { return dist; }
    public void setDist(String dist) { this.dist = dist; }
    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
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
    public boolean isCaptureAsync() { return captureAsync; }
    public void setCaptureAsync(boolean captureAsync) { this.captureAsync = captureAsync; }
    public boolean isCaptureRabbit() { return captureRabbit; }
    public void setCaptureRabbit(boolean captureRabbit) { this.captureRabbit = captureRabbit; }
    public boolean isCaptureKafka() { return captureKafka; }
    public void setCaptureKafka(boolean captureKafka) { this.captureKafka = captureKafka; }
    public boolean isCaptureCache() { return captureCache; }
    public void setCaptureCache(boolean captureCache) { this.captureCache = captureCache; }
    public boolean isCaptureFeign() { return captureFeign; }
    public void setCaptureFeign(boolean captureFeign) { this.captureFeign = captureFeign; }
    public boolean isCaptureValidation() { return captureValidation; }
    public void setCaptureValidation(boolean captureValidation) { this.captureValidation = captureValidation; }
    public boolean isCaptureSecurity() { return captureSecurity; }
    public void setCaptureSecurity(boolean captureSecurity) { this.captureSecurity = captureSecurity; }
    public boolean isCaptureRetry() { return captureRetry; }
    public void setCaptureRetry(boolean captureRetry) { this.captureRetry = captureRetry; }
}
