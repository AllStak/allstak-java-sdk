package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.internal.SdkLogger;
import dev.allstak.model.HttpRequestItem;
import dev.allstak.model.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
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
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Servlet filter that automatically captures inbound HTTP requests for AllStak monitoring.
 * Measures timing, captures method/path/status/sizes, and sends to AllStak.
 */
public class AllStakServletFilter extends OncePerRequestFilter {

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token", "x-allstak-key");
    private static final Pattern CARD_PATTERN = Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b");
    private static final Pattern JWT_PATTERN = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");

    private final AllStakClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

        String traceId = firstNonBlank(traceIdFromTraceparent(request.getHeader("traceparent")),
                request.getHeader("x-allstak-trace-id"),
                UUID.randomUUID().toString());
        String requestId = firstNonBlank(request.getHeader("x-allstak-request-id"), UUID.randomUUID().toString());
        boolean[] exceptionThrown = new boolean[] { false };
        RequestContext ctx = RequestContext.of(
                requestWrapper.getMethod(),
                requestWrapper.getRequestURI(),
                requestWrapper.getServerName(),
                requestWrapper.getHeader("User-Agent"),
                traceId,
                requestId);
        AllStakClient.setRequestContext(ctx);
        MDC.put("traceId", traceId);
        MDC.put("requestId", requestId);
        responseWrapper.setHeader("x-allstak-trace-id", traceId);
        responseWrapper.setHeader("x-allstak-request-id", requestId);

        if (client.getConfig().isAutoBreadcrumbs()) {
            client.addBreadcrumb("http",
                    requestWrapper.getMethod() + " " + requestWrapper.getRequestURI() + " -> processing",
                    "info",
                    Map.of("method", requestWrapper.getMethod(), "path", requestWrapper.getRequestURI(), "host", requestWrapper.getServerName()));
        }

        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
        } catch (IOException | ServletException | RuntimeException e) {
            exceptionThrown[0] = true;
            throw e;
        } finally {
            try {
                long durationMs = System.currentTimeMillis() - startTime;
                String spanId = UUID.randomUUID().toString().substring(0, 16);

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
                    if (!reqHeaders.isEmpty()) reqHeadersJson = objectMapper.writeValueAsString(reqHeaders);
                    if (!resHeaders.isEmpty()) resHeadersJson = objectMapper.writeValueAsString(resHeaders);
                } catch (Exception ignored) {}

                BodyCapture requestBody = captureBody(
                        requestWrapper.getContentAsByteArray(),
                        requestWrapper.getCharacterEncoding(),
                        requestWrapper.getContentType(),
                        requestWrapper.getRequestURI(),
                        "request"
                );
                BodyCapture responseBody = captureBody(
                        responseWrapper.getContentAsByteArray(),
                        responseWrapper.getCharacterEncoding(),
                        responseWrapper.getContentType(),
                        requestWrapper.getRequestURI(),
                        "response"
                );

                HttpRequestItem item = HttpRequestItem.builder()
                        .traceId(traceId)
                        .requestId(requestId)
                        .spanId(spanId)
                        .direction("inbound")
                        .method(requestWrapper.getMethod())
                        .host(requestWrapper.getServerName())
                        .path(requestWrapper.getRequestURI())  // query params are NOT included in getRequestURI
                        .statusCode(responseWrapper.getStatus())
                        .durationMs(durationMs)
                        .requestSize(requestWrapper.getContentLengthLong() > 0 ? requestWrapper.getContentLengthLong() : requestWrapper.getContentAsByteArray().length)
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
                    "",  // root span - no parent
                    requestWrapper.getMethod() + " " + requestWrapper.getRequestURI(),
                    "HTTP " + requestWrapper.getMethod() + " " + requestWrapper.getRequestURI(),
                    exceptionThrown[0] || responseWrapper.getStatus() >= 500 ? "error" : "ok",
                    durationMs,
                    startTime,
                    startTime + durationMs,
                    null,  // uses config service name
                    null,  // uses config environment
                    Map.of(
                        "http.method", requestWrapper.getMethod(),
                        "http.url", requestWrapper.getRequestURI(),
                        "http.status_code", String.valueOf(responseWrapper.getStatus()),
                        "http.host", requestWrapper.getServerName(),
                        "allstak.request_id", requestId
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
            MDC.remove("traceId");
            MDC.remove("requestId");

            // IMPORTANT: copy content to actual response
            responseWrapper.copyBodyToResponse();
        }
    }

    private static String traceIdFromTraceparent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) return null;
        String[] parts = traceparent.split("-");
        if (parts.length >= 2 && parts[1].matches("[0-9a-fA-F]{32}")) return parts[1].toLowerCase();
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private BodyCapture captureBody(byte[] bytes, String encoding, String contentType, String path, String kind) {
        AllStakConfig.BodyCaptureConfig policy = client.getConfig().getHttpBodyCapture();
        if (!policy.isEnabled()) {
            return new BodyCapture(null, "disabled", "HTTP body capture is disabled by SDK configuration.");
        }
        if (isDenied(path, policy)) {
            return new BodyCapture(null, "dropped_by_policy", "Route is denied by HTTP body capture policy.");
        }
        if (!isAllowed(path, policy)) {
            return new BodyCapture(null, "dropped_by_policy", "Route is not allowlisted for HTTP body capture.");
        }
        if (!isAllowedContentType(contentType, policy)) {
            return new BodyCapture(null, "unsupported", "Content type is not allowlisted for HTTP body capture.");
        }
        if (bytes == null || bytes.length == 0) {
            return new BodyCapture(null, "captured", "No " + kind + " body was present.");
        }
        int limit = Math.max(0, policy.getMaxBodySizeBytes());
        boolean truncated = bytes.length > limit;
        int length = truncated ? limit : bytes.length;
        Charset charset = safeCharset(encoding);
        String raw = new String(bytes, 0, length, charset) + (truncated ? "\n[TRUNCATED]" : "");
        String redacted = redactBody(raw, policy);
        boolean changed = !raw.equals(redacted);
        String status = truncated ? "truncated" : changed ? "redacted" : "captured";
        String reason = truncated ? "Body exceeded configured max size of " + limit + " bytes." :
                changed ? "Sensitive fields or values were redacted before transport." : null;
        return new BodyCapture(redacted, status, reason);
    }

    private boolean isAllowed(String path, AllStakConfig.BodyCaptureConfig policy) {
        return policy.getAllowedRoutes().isEmpty() || matchesAny(path, policy.getAllowedRoutes());
    }

    private boolean isDenied(String path, AllStakConfig.BodyCaptureConfig policy) {
        return matchesAny(path, policy.getDeniedRoutes());
    }

    private boolean matchesAny(String path, Set<String> patterns) {
        for (String pattern : patterns) {
            if (pattern.equals(path) || ("*".equals(pattern)) || (pattern.endsWith("*") && path.startsWith(pattern.substring(0, pattern.length() - 1)))) {
                return true;
            }
        }
        return false;
    }

    private boolean isAllowedContentType(String contentType, AllStakConfig.BodyCaptureConfig policy) {
        if (contentType == null || contentType.isBlank()) return false;
        String lower = contentType.toLowerCase();
        for (String allowed : policy.getContentTypes()) {
            if (lower.contains(allowed.toLowerCase())) return true;
        }
        return false;
    }

    private Charset safeCharset(String encoding) {
        try {
            return encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private String redactBody(String raw, AllStakConfig.BodyCaptureConfig policy) {
        String redacted = raw;
        Set<String> keys = new java.util.HashSet<>(Set.of(
                "password", "passcode", "authorization", "cookie", "otp", "token", "jwt",
                "secret", "refreshToken", "refresh_token", "iban", "nationalId", "national_id",
                "sessionId", "session_id", "creditCard", "credit_card", "cardNumber", "card_number"));
        keys.addAll(policy.getRedactedFields());
        for (String key : keys) {
            redacted = redacted.replaceAll("(?i)(\"" + Pattern.quote(key) + "\"\\s*:\\s*\")([^\"]*)(\")", "$1[REDACTED]$3");
            redacted = redacted.replaceAll("(?i)(" + Pattern.quote(key) + "\\s*=\\s*)([^&\\s]+)", "$1[REDACTED]");
        }
        redacted = JWT_PATTERN.matcher(redacted).replaceAll("[REDACTED_JWT]");
        redacted = CARD_PATTERN.matcher(redacted).replaceAll("[REDACTED_CARD]");
        redacted = redacted.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]");
        return redacted;
    }

    private record BodyCapture(String body, String status, String reason) {}

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Skip actuator and health endpoints
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.equals("/health");
    }
}
