package dev.allstak.okhttp;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import dev.allstak.tracing.TracePropagationDecider;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * OkHttp {@link Interceptor} that emits one AllStak span + breadcrumb per
 * call and injects the SDK's trace headers ({@code x-allstak-trace-id},
 * {@code x-allstak-span-id}, and the W3C {@code traceparent} sampled flag)
 * on outbound calls — gated by {@link TracePropagationDecider} so trace
 * context never leaks to third-party hosts that aren't on the allowlist.
 *
 * <p>Register on every {@code OkHttpClient} that should be instrumented:
 *
 * <pre>{@code
 * OkHttpClient client = new OkHttpClient.Builder()
 *     .addInterceptor(new AllStakOkHttpInterceptor())
 *     .build();
 * }</pre>
 *
 * <p>Spring Boot users can let
 * {@code dev.allstak.spring.AllStakOkHttpAutoConfiguration} install it on
 * every {@code OkHttpClient} bean (when allstak-spring-boot-starter is on
 * the classpath together with this module).
 */
public final class AllStakOkHttpInterceptor implements Interceptor {

    public static final String HEADER_TRACE_ID    = "x-allstak-trace-id";
    public static final String HEADER_SPAN_ID     = "x-allstak-span-id";
    public static final String HEADER_TRACEPARENT = "traceparent";

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        AllStakClient client = AllStak.getClient();

        long startNanos = System.nanoTime();
        String url = request.url().toString();
        String method = request.method();
        String host = request.url().host();
        // Attach to the in-flight transaction/span when one is active on this
        // thread so the HTTP call nests under it (shared trace id, this call's
        // span pointing at the active span as its parent). Falls back to a
        // fresh root trace when no transaction is open — preserving the
        // pre-tracing-API behavior exactly.
        dev.allstak.tracing.Span currentSpan = AllStak.getCurrentSpan();
        String traceId = currentSpan != null ? currentSpan.getTraceId() : newTraceId();
        String parentSpanId = currentSpan != null ? currentSpan.getSpanId() : null;
        String spanId = newSpanId();

        // Inject trace headers only when (a) client initialised AND (b) the
        // outbound target is on the propagation allowlist. Otherwise the
        // upstream chain runs unmodified — important when the URL belongs to
        // a third-party service the customer doesn't want their internal
        // trace context shared with.
        Request enriched = request;
        if (client != null && client.getConfig().getTracePropagationDecider().shouldPropagate(url)) {
            enriched = request.newBuilder()
                    .header(HEADER_TRACE_ID, traceId)
                    .header(HEADER_SPAN_ID, spanId)
                    .header(HEADER_TRACEPARENT, traceparent(traceId, spanId, client))
                    .build();
        }

        Response response = null;
        Throwable failure = null;
        try {
            response = chain.proceed(enriched);
            return response;
        } catch (IOException | RuntimeException t) {
            failure = t;
            throw t;
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            int status = response != null ? response.code() : 0;

            // Breadcrumb summarising the call — survives into the next
            // captured error if any.
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("method", method);
            data.put("host", host);
            data.put("status_code", status);
            data.put("duration_ms", durationMs);
            if (failure != null) data.put("error", failure.getClass().getSimpleName());
            AllStak.addBreadcrumb("http", method + " " + host, levelFor(status, failure), data);

            // Span — fire-and-forget; ignored if the SDK isn't initialised.
            if (client != null) {
                try {
                    client.captureSpan(
                            traceId, spanId, parentSpanId,
                            "http.client",
                            method + " " + url,
                            failure == null && status > 0 && status < 400 ? "ok" : "error",
                            durationMs,
                            System.currentTimeMillis() - durationMs,
                            System.currentTimeMillis(),
                            "okhttp",
                            client.getConfig().getEnvironment(),
                            null);
                } catch (Exception ignored) {
                    // Span capture must never poison the host call.
                }
            }
        }
    }

    private static String levelFor(int status, Throwable failure) {
        if (failure != null) return "error";
        if (status >= 500) return "error";
        if (status >= 400) return "warn";
        return "info";
    }

    private static String traceparent(String traceId, String spanId, AllStakClient client) {
        String flag = client.traceparentSampledFlag();
        String hex32 = pad(traceId.replace("-", ""), 32);
        String hex16 = pad(spanId.replace("-", ""), 16);
        return "00-" + hex32 + "-" + hex16 + "-" + flag;
    }

    private static String pad(String hex, int width) {
        if (hex.length() == width) return hex;
        if (hex.length() > width) return hex.substring(0, width);
        StringBuilder b = new StringBuilder(width);
        b.append(hex);
        while (b.length() < width) b.append('0');
        return b.toString();
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String newSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
