package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.internal.SdkLogger;
import dev.allstak.masking.DataMasker;
import dev.allstak.model.HttpRequestItem;
import dev.allstak.model.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Servlet filter that automatically captures inbound HTTP requests for AllStak monitoring.
 * Measures timing, captures method/path/status/sizes, and sends to AllStak.
 */
public class AllStakServletFilter extends OncePerRequestFilter {

    private static final int MAX_BODY_CHARS = 8192;

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token", "x-allstak-key");

    private final AllStakClient client;

    public AllStakServletFilter(AllStakClient client) {
        this.client = client;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String timestamp = Instant.now().toString();
        long startTime = System.currentTimeMillis();
        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        AllStakTraceHeaders headers = AllStakTraceHeaders.from(requestWrapper);
        String traceId = headers.traceId;
        String spanId = AllStakTraceHeaders.randomSpanId();
        RequestContext ctx = RequestContext.of(
                requestWrapper.getMethod(),
                requestWrapper.getRequestURI(),
                requestWrapper.getServerName(),
                requestWrapper.getHeader("User-Agent"),
                traceId);
        AllStakClient.setRequestContext(ctx);
        responseWrapper.setHeader("X-AllStak-Trace-Id", traceId);
        responseWrapper.setHeader("X-AllStak-Request-Id", headers.requestId);
        responseWrapper.setHeader("X-AllStak-Span-Id", spanId);
        responseWrapper.setHeader("traceparent", "00-" + traceId + "-" + spanId + "-" + client.traceparentSampledFlag());
        responseWrapper.setHeader("baggage", AllStakTraceHeaders.mergeBaggage(
                requestWrapper.getHeader("baggage"), traceId, headers.requestId, spanId));
        responseWrapper.setHeader("AllStak-Baggage", AllStakTraceHeaders.baggage(traceId, headers.requestId, spanId));

        if (client.getConfig().isAutoBreadcrumbs()) {
            client.addBreadcrumb("http",
                    requestWrapper.getMethod() + " " + requestWrapper.getRequestURI() + " -> processing",
                    "info",
                    Map.of("method", requestWrapper.getMethod(), "path", requestWrapper.getRequestURI(), "host", requestWrapper.getServerName()));
        }

        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
        } finally {
            try {
                long durationMs = System.currentTimeMillis() - startTime;

                // Capture request headers (sanitized)
                Map<String, String> reqHeaders = new LinkedHashMap<>();
                Enumeration<String> headerNames = requestWrapper.getHeaderNames();
                if (headerNames != null) {
                    while (headerNames.hasMoreElements()) {
                        String name = headerNames.nextElement();
                        String lowerName = name.toLowerCase();
                        if (SENSITIVE_HEADERS.contains(lowerName)) {
                            reqHeaders.put(name, "[REDACTED]");
                        } else {
                            reqHeaders.put(name, requestWrapper.getHeader(name));
                        }
                    }
                }

                // Capture response headers (sanitized)
                Map<String, String> resHeaders = new LinkedHashMap<>();
                for (String name : responseWrapper.getHeaderNames()) {
                    String lowerName = name.toLowerCase();
                    if (SENSITIVE_HEADERS.contains(lowerName)) {
                        resHeaders.put(name, "[REDACTED]");
                    } else {
                        resHeaders.put(name, responseWrapper.getHeader(name));
                    }
                }

                // Serialize headers to JSON
                String reqHeadersJson = null;
                String resHeadersJson = null;
                try {
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    if (!reqHeaders.isEmpty()) reqHeadersJson = mapper.writeValueAsString(reqHeaders);
                    if (!resHeaders.isEmpty()) resHeadersJson = mapper.writeValueAsString(resHeaders);
                } catch (Exception ignored) {}

                BodyCapture requestBody = captureBody(
                        requestWrapper.getContentAsByteArray(),
                        requestWrapper.getContentType(),
                        requestWrapper.getCharacterEncoding(),
                        "request");
                BodyCapture responseBody = captureBody(
                        responseWrapper.getContentAsByteArray(),
                        responseWrapper.getContentType(),
                        responseWrapper.getCharacterEncoding(),
                        "response");

                HttpRequestItem item = HttpRequestItem.builder()
                        .traceId(traceId)
                        .requestId(headers.requestId)
                        .spanId(spanId)
                        .parentSpanId(headers.parentSpanId)
                        .direction("inbound")
                        .method(requestWrapper.getMethod())
                        .host(requestWrapper.getServerName())
                        .path(requestWrapper.getRequestURI())  // query params are NOT included in getRequestURI
                        .statusCode(responseWrapper.getStatus())
                        .durationMs(durationMs)
                        .requestSize(requestWrapper.getContentLengthLong() > 0 ? requestWrapper.getContentLengthLong() : 0)
                        .responseSize(responseWrapper.getContentSize())
                        .userId(requestWrapper.getRemoteUser())
                        .timestamp(timestamp)
                        .requestHeaders(reqHeadersJson)
                        .responseHeaders(resHeadersJson)
                        .requestBody(requestBody.body())
                        .responseBody(responseBody.body())
                        .requestBodyCaptureStatus(requestBody.status())
                        .responseBodyCaptureStatus(responseBody.status())
                        .requestBodyCaptureReason(requestBody.reason())
                        .responseBodyCaptureReason(responseBody.reason())
                        .environment(client.getConfig().getEnvironment())
                        .release(client.getConfig().getRelease())
                        .build();

                client.captureHttpRequest(item);

                // Send trace span
                client.captureSpan(
                    traceId,
                    spanId,
                    headers.parentSpanId,
                    requestWrapper.getMethod() + " " + requestWrapper.getRequestURI(),
                    "HTTP " + requestWrapper.getMethod() + " " + requestWrapper.getRequestURI(),
                    responseWrapper.getStatus() >= 500 ? "error" : "ok",
                    durationMs,
                    startTime,
                    startTime + durationMs,
                    null,  // uses config service name
                    null,  // uses config environment
                    Map.of(
                        "http.method", requestWrapper.getMethod(),
                        "http.url", requestWrapper.getRequestURI(),
                        "http.status_code", String.valueOf(responseWrapper.getStatus()),
                        "http.host", requestWrapper.getServerName()
                    )
                );

                if (client.getConfig().isAutoBreadcrumbs()) {
                    client.addBreadcrumb("http",
                            requestWrapper.getMethod() + " " + requestWrapper.getRequestURI() + " -> " + responseWrapper.getStatus(),
                            responseWrapper.getStatus() >= 400 ? "error" : "info",
                            Map.of("method", requestWrapper.getMethod(), "path", requestWrapper.getRequestURI(),
                                   "statusCode", responseWrapper.getStatus(), "durationMs", durationMs));
                }
            } catch (Exception e) {
                SdkLogger.debug("Failed to capture HTTP request in filter: {}", e.getMessage());
            }

            AllStakClient.clearRequestContext();

            // IMPORTANT: copy content to actual response
            responseWrapper.copyBodyToResponse();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Skip actuator and health endpoints
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.equals("/health");
    }

    private static BodyCapture captureBody(byte[] bytes, String contentType, String encoding, String direction) {
        if (bytes == null || bytes.length == 0) {
            return new BodyCapture(null, "disabled", direction + " body is empty.");
        }
        if (!isBodyCaptureSupported(contentType)) {
            return new BodyCapture(null, "unsupported", direction + " body content type is not supported.");
        }

        String body = new String(bytes, resolveCharset(encoding));
        boolean truncated = body.length() > MAX_BODY_CHARS;
        if (truncated) {
            body = body.substring(0, MAX_BODY_CHARS);
        }

        String masked = DataMasker.maskBody(body, contentType);
        boolean redacted = !body.equals(masked);
        if (truncated) {
            return new BodyCapture(masked, "truncated", direction + " body captured and truncated.");
        }
        if (redacted) {
            return new BodyCapture(masked, "redacted", direction + " body captured with sensitive fields redacted.");
        }
        return new BodyCapture(masked, "captured", direction + " body captured.");
    }

    private static boolean isBodyCaptureSupported(String contentType) {
        if (contentType == null || contentType.isBlank()) return true;
        String lower = contentType.toLowerCase();
        return lower.startsWith("text/")
                || lower.contains("application/json")
                || lower.contains("+json")
                || lower.contains("application/xml")
                || lower.contains("+xml")
                || lower.contains("application/x-www-form-urlencoded")
                || lower.contains("application/graphql");
    }

    private static Charset resolveCharset(String encoding) {
        try {
            return encoding == null || encoding.isBlank()
                    ? StandardCharsets.UTF_8
                    : Charset.forName(encoding);
        } catch (Exception ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    private record BodyCapture(String body, String status, String reason) {}
}
