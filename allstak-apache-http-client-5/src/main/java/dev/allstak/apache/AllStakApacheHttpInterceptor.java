package dev.allstak.apache;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpResponseInterceptor;
import org.apache.hc.core5.http.protocol.HttpContext;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Apache HttpClient 5 request + response interceptors. Capture span,
 * breadcrumb, and inject trace headers on outbound calls — both halves
 * implemented here so a single instance can be registered on each side
 * of the chain. Gated by the SDK's {@code tracePropagationTargets}.
 */
public final class AllStakApacheHttpInterceptor implements HttpRequestInterceptor, HttpResponseInterceptor {

    public static final String HEADER_TRACE_ID    = "x-allstak-trace-id";
    public static final String HEADER_SPAN_ID     = "x-allstak-span-id";
    public static final String HEADER_TRACEPARENT = "traceparent";

    private static final String CTX_TRACE_ID  = "allstak.traceId";
    private static final String CTX_SPAN_ID   = "allstak.spanId";
    private static final String CTX_START_NS  = "allstak.startNs";

    @Override
    public void process(HttpRequest request, EntityDetails entity, HttpContext context) throws HttpException, IOException {
        AllStakClient client = AllStak.getClient();
        String traceId = newId(32);
        String spanId  = newId(16);
        context.setAttribute(CTX_TRACE_ID, traceId);
        context.setAttribute(CTX_SPAN_ID, spanId);
        context.setAttribute(CTX_START_NS, System.nanoTime());

        if (client == null) return;
        String url;
        try { url = request.getUri().toString(); } catch (Exception e) { return; }
        if (!client.getConfig().getTracePropagationDecider().shouldPropagate(url)) return;
        request.addHeader(HEADER_TRACE_ID, traceId);
        request.addHeader(HEADER_SPAN_ID, spanId);
        request.addHeader(HEADER_TRACEPARENT, "00-" + pad(traceId, 32) + "-" + pad(spanId, 16) + "-" + client.traceparentSampledFlag());
    }

    @Override
    public void process(HttpResponse response, EntityDetails entity, HttpContext context) throws HttpException, IOException {
        AllStakClient client = AllStak.getClient();
        if (client == null) return;
        Long startNs = (Long) context.getAttribute(CTX_START_NS);
        long durationMs = startNs == null ? 0L : (System.nanoTime() - startNs) / 1_000_000L;
        int status = response.getCode();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status_code", status);
        data.put("duration_ms", durationMs);
        AllStak.addBreadcrumb("http", "apache " + status, status >= 500 ? "error" : status >= 400 ? "warn" : "info", data);

        String traceId = (String) context.getAttribute(CTX_TRACE_ID);
        String spanId  = (String) context.getAttribute(CTX_SPAN_ID);
        if (traceId == null || spanId == null) return;
        try {
            client.captureSpan(traceId, spanId, null, "http.client", "apache call",
                    status > 0 && status < 400 ? "ok" : "error",
                    durationMs,
                    System.currentTimeMillis() - durationMs,
                    System.currentTimeMillis(),
                    "apache-http-client-5",
                    client.getConfig().getEnvironment(),
                    null);
        } catch (Exception ignored) { }
    }

    private static String newId(int chars) {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return hex.length() >= chars ? hex.substring(0, chars) : pad(hex, chars);
    }

    private static String pad(String hex, int width) {
        if (hex.length() == width) return hex;
        if (hex.length() > width) return hex.substring(0, width);
        StringBuilder b = new StringBuilder(width).append(hex);
        while (b.length() < width) b.append('0');
        return b.toString();
    }
}
