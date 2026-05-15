package dev.allstak.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class HttpRequestItem {

    private final String traceId;
    private final String requestId;
    private final String spanId;
    private final String parentSpanId;
    private final String direction;
    private final String method;
    private final String host;
    private final String path;
    private final int statusCode;
    private final long durationMs;
    private final long requestSize;
    private final long responseSize;
    private final String userId;
    private final String errorFingerprint;
    private final String timestamp;
    private final String requestHeaders;
    private final String responseHeaders;
    private final String requestBody;
    private final String responseBody;
    private final String requestBodyCaptureStatus;
    private final String responseBodyCaptureStatus;
    private final String requestBodyCaptureReason;
    private final String responseBodyCaptureReason;
    private final String environment;
    private final String release;

    private HttpRequestItem(Builder builder) {
        this.traceId = builder.traceId;
        this.requestId = builder.requestId;
        this.spanId = builder.spanId;
        this.parentSpanId = builder.parentSpanId;
        this.direction = builder.direction;
        this.method = builder.method;
        this.host = builder.host;
        this.path = builder.path;
        this.statusCode = builder.statusCode;
        this.durationMs = builder.durationMs;
        this.requestSize = builder.requestSize;
        this.responseSize = builder.responseSize;
        this.userId = builder.userId;
        this.errorFingerprint = builder.errorFingerprint;
        this.timestamp = builder.timestamp;
        this.requestHeaders = builder.requestHeaders;
        this.responseHeaders = builder.responseHeaders;
        this.requestBody = builder.requestBody;
        this.responseBody = builder.responseBody;
        this.requestBodyCaptureStatus = builder.requestBodyCaptureStatus;
        this.responseBodyCaptureStatus = builder.responseBodyCaptureStatus;
        this.requestBodyCaptureReason = builder.requestBodyCaptureReason;
        this.responseBodyCaptureReason = builder.responseBodyCaptureReason;
        this.environment = builder.environment;
        this.release = builder.release;
    }

    public String getTraceId() { return traceId; }
    public String getRequestId() { return requestId; }
    public String getSpanId() { return spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getDirection() { return direction; }
    public String getMethod() { return method; }
    public String getHost() { return host; }
    public String getPath() { return path; }
    public int getStatusCode() { return statusCode; }
    public long getDurationMs() { return durationMs; }
    public long getRequestSize() { return requestSize; }
    public long getResponseSize() { return responseSize; }
    public String getUserId() { return userId; }
    public String getErrorFingerprint() { return errorFingerprint; }
    public String getTimestamp() { return timestamp; }
    public String getRequestHeaders() { return requestHeaders; }
    public String getResponseHeaders() { return responseHeaders; }
    public String getRequestBody() { return requestBody; }
    public String getResponseBody() { return responseBody; }
    public String getRequestBodyCaptureStatus() { return requestBodyCaptureStatus; }
    public String getResponseBodyCaptureStatus() { return responseBodyCaptureStatus; }
    public String getRequestBodyCaptureReason() { return requestBodyCaptureReason; }
    public String getResponseBodyCaptureReason() { return responseBodyCaptureReason; }
    public String getEnvironment() { return environment; }
    public String getRelease() { return release; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String traceId;
        private String requestId;
        private String spanId;
        private String parentSpanId;
        private String direction;
        private String method;
        private String host;
        private String path;
        private int statusCode;
        private long durationMs;
        private long requestSize;
        private long responseSize;
        private String userId;
        private String errorFingerprint;
        private String timestamp;
        private String requestHeaders;
        private String responseHeaders;
        private String requestBody;
        private String responseBody;
        private String requestBodyCaptureStatus;
        private String responseBodyCaptureStatus;
        private String requestBodyCaptureReason;
        private String responseBodyCaptureReason;
        private String environment;
        private String release;

        public Builder traceId(String traceId) { this.traceId = traceId; return this; }
        public Builder requestId(String requestId) { this.requestId = requestId; return this; }
        public Builder spanId(String spanId) { this.spanId = spanId; return this; }
        public Builder parentSpanId(String parentSpanId) { this.parentSpanId = parentSpanId; return this; }
        public Builder direction(String direction) { this.direction = direction; return this; }
        public Builder method(String method) { this.method = method; return this; }
        public Builder host(String host) { this.host = host; return this; }
        public Builder path(String path) { this.path = path; return this; }
        public Builder statusCode(int statusCode) { this.statusCode = statusCode; return this; }
        public Builder durationMs(long durationMs) { this.durationMs = durationMs; return this; }
        public Builder requestSize(long requestSize) { this.requestSize = requestSize; return this; }
        public Builder responseSize(long responseSize) { this.responseSize = responseSize; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder errorFingerprint(String errorFingerprint) { this.errorFingerprint = errorFingerprint; return this; }
        public Builder timestamp(String timestamp) { this.timestamp = timestamp; return this; }
        public Builder requestHeaders(String requestHeaders) { this.requestHeaders = requestHeaders; return this; }
        public Builder responseHeaders(String responseHeaders) { this.responseHeaders = responseHeaders; return this; }
        public Builder requestBody(String requestBody) { this.requestBody = requestBody; return this; }
        public Builder responseBody(String responseBody) { this.responseBody = responseBody; return this; }
        public Builder requestBodyCaptureStatus(String requestBodyCaptureStatus) { this.requestBodyCaptureStatus = requestBodyCaptureStatus; return this; }
        public Builder responseBodyCaptureStatus(String responseBodyCaptureStatus) { this.responseBodyCaptureStatus = responseBodyCaptureStatus; return this; }
        public Builder requestBodyCaptureReason(String requestBodyCaptureReason) { this.requestBodyCaptureReason = requestBodyCaptureReason; return this; }
        public Builder responseBodyCaptureReason(String responseBodyCaptureReason) { this.responseBodyCaptureReason = responseBodyCaptureReason; return this; }
        public Builder environment(String environment) { this.environment = environment; return this; }
        public Builder release(String release) { this.release = release; return this; }

        public HttpRequestItem build() { return new HttpRequestItem(this); }
    }
}
