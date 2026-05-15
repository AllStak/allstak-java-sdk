package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.model.RequestContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;

import java.util.UUID;

public final class AllStakFeignRequestInterceptor implements RequestInterceptor {
    static final String HEADER_TRACE_ID = "x-allstak-trace-id";
    static final String HEADER_REQUEST_ID = "x-allstak-request-id";
    static final String HEADER_TRACEPARENT = "traceparent";

    @Override
    public void apply(RequestTemplate template) {
        try {
            RequestContext req = AllStakClient.getRequestContext();
            String traceId = req != null && req.getTraceId() != null ? req.getTraceId() : UUID.randomUUID().toString();
            String requestId = req != null && req.getRequestId() != null ? req.getRequestId() : "feign-" + UUID.randomUUID();
            if (!template.headers().containsKey(HEADER_TRACE_ID)) template.header(HEADER_TRACE_ID, traceId);
            if (!template.headers().containsKey(HEADER_REQUEST_ID)) template.header(HEADER_REQUEST_ID, requestId);
            if (!template.headers().containsKey(HEADER_TRACEPARENT)) template.header(HEADER_TRACEPARENT, toTraceparent(traceId));
        } catch (Exception ignored) {
            // Feign header injection is best-effort and must never break calls.
        }
    }

    private static String toTraceparent(String traceId) {
        String normalizedTrace = traceId.replace("-", "");
        if (normalizedTrace.length() < 32) normalizedTrace = (normalizedTrace + "00000000000000000000000000000000").substring(0, 32);
        if (normalizedTrace.length() > 32) normalizedTrace = normalizedTrace.substring(0, 32);
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "00-" + normalizedTrace + "-" + spanId + "-01";
    }
}
