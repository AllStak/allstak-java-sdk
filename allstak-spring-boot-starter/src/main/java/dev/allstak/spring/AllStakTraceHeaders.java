package dev.allstak.spring;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class AllStakTraceHeaders {
    private static final SecureRandom RANDOM = new SecureRandom();

    final String traceId;
    final String parentSpanId;
    final String requestId;

    private AllStakTraceHeaders(String traceId, String parentSpanId, String requestId) {
        this.traceId = traceId;
        this.parentSpanId = parentSpanId;
        this.requestId = requestId;
    }

    static AllStakTraceHeaders from(HttpServletRequest request) {
        String traceparent = request.getHeader("traceparent");
        String traceId = traceIdFromTraceparent(traceparent);
        if (isBlank(traceId)) traceId = firstValidTraceHeader(request, "X-AllStak-Trace-Id", "X-Trace-Id");
        String parentSpanId = parentSpanIdFromTraceparent(traceparent);
        if (isBlank(parentSpanId) && !isBlank(traceId)) {
            parentSpanId = firstValidSpanHeader(request, "X-AllStak-Span-Id", "X-Span-Id");
        }
        String requestId = firstHeader(request, "X-Request-Id", "X-AllStak-Request-Id");
        return new AllStakTraceHeaders(
                isBlank(traceId) ? randomTraceId() : traceId,
                parentSpanId == null ? "" : parentSpanId,
                isBlank(requestId) ? randomTraceId() : requestId);
    }

    static String randomTraceId() {
        return randomHex(16);
    }

    static String randomSpanId() {
        return randomHex(8);
    }

    static String baggage(String traceId, String requestId, String spanId) {
        List<String> parts = new ArrayList<>();
        if (!isBlank(traceId)) parts.add("allstak-trace_id=" + traceId);
        if (!isBlank(requestId)) parts.add("allstak-request_id=" + requestId);
        if (!isBlank(spanId)) parts.add("allstak-span_id=" + spanId);
        return String.join(",", parts);
    }

    static String mergeBaggage(String existing, String traceId, String requestId, String spanId) {
        List<String> parts = new ArrayList<>();
        if (!isBlank(existing)) {
            for (String part : existing.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !trimmed.toLowerCase().startsWith("allstak-")) {
                    parts.add(trimmed);
                }
            }
        }
        String own = baggage(traceId, requestId, spanId);
        if (!own.isEmpty()) {
            for (String part : own.split(",")) parts.add(part);
        }
        return String.join(",", parts);
    }

    static void apply(HttpHeaders target, String traceId, String requestId, String spanId) {
        // Backwards-compatible: defaults to the sampled flag ("01").
        apply(target, traceId, requestId, spanId, "01");
    }

    /**
     * @param sampledFlag the W3C trace flags byte: {@code "01"} sampled,
     *                    {@code "00"} not sampled.
     */
    static void apply(HttpHeaders target, String traceId, String requestId, String spanId, String sampledFlag) {
        String flag = sampledFlag != null && sampledFlag.matches("(?i)^[0-9a-f]{2}$") ? sampledFlag.toLowerCase(Locale.ROOT) : "01";
        String wireTraceId = normalizeTraceId(traceId);
        String wireSpanId = isBlank(spanId) ? null : normalizeSpanId(spanId);
        target.set("X-AllStak-Trace-Id", wireTraceId);
        if (!isBlank(requestId)) target.set("X-AllStak-Request-Id", requestId);
        if (!isBlank(wireSpanId)) {
            target.set("X-AllStak-Span-Id", wireSpanId);
            target.set("traceparent", "00-" + wireTraceId + "-" + wireSpanId + "-" + flag);
        }
        target.set("baggage", mergeBaggage(target.getFirst("baggage"), wireTraceId, requestId, wireSpanId));
        target.set("AllStak-Baggage", baggage(wireTraceId, requestId, wireSpanId));
    }

    private static String firstHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            String value = request.getHeader(name);
            if (!isBlank(value)) return value.trim();
        }
        return null;
    }

    private static String traceIdFromTraceparent(String traceparent) {
        if (traceparent == null) return null;
        String[] parts = traceparent.trim().split("-");
        if (parts.length != 4 || !"00".equals(parts[0]) || !parts[3].matches("(?i)^[0-9a-f]{2}$")) return null;
        String traceId = parts[1].toLowerCase(Locale.ROOT);
        return isValidTraceId(traceId) ? traceId : null;
    }

    private static String parentSpanIdFromTraceparent(String traceparent) {
        if (traceparent == null) return null;
        String[] parts = traceparent.trim().split("-");
        if (parts.length != 4 || !"00".equals(parts[0]) || !parts[3].matches("(?i)^[0-9a-f]{2}$")) return null;
        String spanId = parts[2].toLowerCase(Locale.ROOT);
        return isValidSpanId(spanId) ? spanId : null;
    }

    private static String firstValidTraceHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            String value = request.getHeader(name);
            if (!isBlank(value)) {
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                if (isValidTraceId(normalized)) return normalized;
            }
        }
        return null;
    }

    private static String firstValidSpanHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            String value = request.getHeader(name);
            if (!isBlank(value)) {
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                if (isValidSpanId(normalized)) return normalized;
            }
        }
        return null;
    }

    static String normalizeTraceId(String traceId) {
        String hex = traceId == null ? "" : traceId.replaceAll("[^0-9A-Fa-f]", "").toLowerCase(Locale.ROOT);
        String candidate = hex.length() >= 32
                ? hex.substring(0, 32)
                : (!hex.isEmpty() ? String.format("%-32s", hex).replace(' ', '0') : "");
        return isValidTraceId(candidate) ? candidate : randomTraceId();
    }

    static String normalizeSpanId(String spanId) {
        String hex = spanId == null ? "" : spanId.replaceAll("[^0-9A-Fa-f]", "").toLowerCase(Locale.ROOT);
        String candidate = hex.length() >= 16
                ? hex.substring(0, 16)
                : (!hex.isEmpty() ? String.format("%-16s", hex).replace(' ', '0') : "");
        return isValidSpanId(candidate) ? candidate : randomSpanId();
    }

    private static boolean isValidTraceId(String traceId) {
        return traceId != null
                && traceId.matches("^[0-9a-f]{32}$")
                && !traceId.matches("^0{32}$");
    }

    private static boolean isValidSpanId(String spanId) {
        return spanId != null
                && spanId.matches("^[0-9a-f]{16}$")
                && !spanId.matches("^0{16}$");
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        StringBuilder out = new StringBuilder(bytes * 2);
        for (byte b : data) out.append(String.format("%02x", b));
        String value = out.toString();
        if (value.matches("^0+$")) return "1" + value.substring(1);
        return value;
    }
}
