package dev.allstak.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ErrorEvent {

    private final String exceptionClass;
    private final String message;
    private final List<String> stackTrace;
    private final String level;
    private final String environment;
    private final String release;
    private final String sessionId;
    private final UserContext user;
    private final Map<String, Object> metadata;
    private final String traceId;
    private final String requestId;
    private final RequestContext requestContext;
    private final List<Breadcrumb> breadcrumbs;
    // ── Phase 2 — v2 ingest contract additions ────────────────
    private final String platform;
    private final String sdkName;
    private final String sdkVersion;
    private final String dist;
    private final List<Frame> frames;

    public ErrorEvent(String exceptionClass, String message, List<String> stackTrace,
                      String level, String environment, String release,
                      String sessionId, UserContext user, Map<String, Object> metadata) {
        this(exceptionClass, message, stackTrace, level, environment, release,
                sessionId, user, metadata, null, null, null);
    }

    public ErrorEvent(String exceptionClass, String message, List<String> stackTrace,
                      String level, String environment, String release,
                      String sessionId, UserContext user, Map<String, Object> metadata,
                      String traceId, RequestContext requestContext) {
        this(exceptionClass, message, stackTrace, level, environment, release,
                sessionId, user, metadata, traceId, requestContext, null);
    }

    public ErrorEvent(String exceptionClass, String message, List<String> stackTrace,
                      String level, String environment, String release,
                      String sessionId, UserContext user, Map<String, Object> metadata,
                      String traceId, RequestContext requestContext,
                      List<Breadcrumb> breadcrumbs) {
        this(exceptionClass, message, stackTrace, level, environment, release,
                sessionId, user, metadata, traceId, requestContext, breadcrumbs,
                null, null, null, null, null);
    }

    /** v2 constructor — all fields. */
    public ErrorEvent(String exceptionClass, String message, List<String> stackTrace,
                      String level, String environment, String release,
                      String sessionId, UserContext user, Map<String, Object> metadata,
                      String traceId, RequestContext requestContext,
                      List<Breadcrumb> breadcrumbs,
                      String platform, String sdkName, String sdkVersion,
                      String dist, List<Frame> frames) {
        this(exceptionClass, message, stackTrace, level, environment, release,
                sessionId, user, metadata, traceId, null, requestContext, breadcrumbs,
                platform, sdkName, sdkVersion, dist, frames);
    }

    /** v2.1 constructor — canonical requestId added. */
    public ErrorEvent(String exceptionClass, String message, List<String> stackTrace,
                      String level, String environment, String release,
                      String sessionId, UserContext user, Map<String, Object> metadata,
                      String traceId, String requestId, RequestContext requestContext,
                      List<Breadcrumb> breadcrumbs,
                      String platform, String sdkName, String sdkVersion,
                      String dist, List<Frame> frames) {
        this.exceptionClass = exceptionClass;
        this.message = message;
        this.stackTrace = stackTrace;
        this.level = level;
        this.environment = environment;
        this.release = release;
        this.sessionId = sessionId;
        this.user = user;
        this.metadata = metadata;
        this.traceId = traceId;
        this.requestId = requestId;
        this.requestContext = requestContext;
        this.breadcrumbs = breadcrumbs;
        this.platform = platform;
        this.sdkName = sdkName;
        this.sdkVersion = sdkVersion;
        this.dist = dist;
        this.frames = frames;
    }

    public String getExceptionClass() { return exceptionClass; }
    public String getMessage() { return message; }
    public List<String> getStackTrace() { return stackTrace; }
    public String getLevel() { return level; }
    public String getEnvironment() { return environment; }
    public String getRelease() { return release; }
    public String getSessionId() { return sessionId; }
    public UserContext getUser() { return user; }
    public Map<String, Object> getMetadata() { return metadata; }
    public String getTraceId() { return traceId; }
    public String getRequestId() { return requestId; }
    public RequestContext getRequestContext() { return requestContext; }
    public List<Breadcrumb> getBreadcrumbs() { return breadcrumbs; }
    public String getPlatform() { return platform; }
    public String getSdkName() { return sdkName; }
    public String getSdkVersion() { return sdkVersion; }
    public String getDist() { return dist; }
    public List<Frame> getFrames() { return frames; }
}
