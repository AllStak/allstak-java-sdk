package dev.allstak.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Per-request context attached to error events. Wire shape matches the backend's
 * ErrorIngestRequest.RequestContext: {method, path, host, statusCode, userAgent}.
 *
 * <p>{@code traceId} is also tracked here so the SDK can pull it onto the
 * top-level ErrorEvent.traceId field, but it is intentionally not serialized
 * inside the requestContext object (the backend would ignore it).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class RequestContext {

    private final String method;
    private final String path;
    private final String host;
    private final Integer statusCode;
    private final String userAgent;
    private final String traceId;
    private final String requestId;

    private RequestContext(String method, String path, String host,
                           Integer statusCode, String userAgent, String traceId, String requestId) {
        this.method = method;
        this.path = path;
        this.host = host;
        this.statusCode = statusCode;
        this.userAgent = userAgent;
        this.traceId = traceId;
        this.requestId = requestId;
    }

    public static RequestContext of(String method, String path, String host, String traceId) {
        return new RequestContext(method, path, host, null, null, traceId, null);
    }

    public static RequestContext of(String method, String path, String host,
                                    String userAgent, String traceId) {
        return new RequestContext(method, path, host, null, userAgent, traceId, null);
    }

    public static RequestContext of(String method, String path, String host,
                                    String userAgent, String traceId, String requestId) {
        return new RequestContext(method, path, host, null, userAgent, traceId, requestId);
    }

    public static RequestContext of(String method, String path, String host,
                                    Integer statusCode, String userAgent, String traceId) {
        return new RequestContext(method, path, host, statusCode, userAgent, traceId, null);
    }

    public static RequestContext of(String method, String path, String host,
                                    Integer statusCode, String userAgent, String traceId, String requestId) {
        return new RequestContext(method, path, host, statusCode, userAgent, traceId, requestId);
    }

    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getHost() { return host; }
    public Integer getStatusCode() { return statusCode; }
    public String getUserAgent() { return userAgent; }

    /** Not serialized — used internally to copy onto ErrorEvent.traceId. */
    @JsonIgnore
    public String getTraceId() { return traceId; }

    @JsonIgnore
    public String getRequestId() { return requestId; }
}
