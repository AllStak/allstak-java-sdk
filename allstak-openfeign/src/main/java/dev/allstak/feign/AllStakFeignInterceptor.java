package dev.allstak.feign;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import feign.RequestInterceptor;
import feign.RequestTemplate;

import java.util.UUID;

/**
 * OpenFeign request interceptor — injects AllStak trace headers gated by
 * {@link dev.allstak.tracing.TracePropagationDecider}. Register with the
 * Feign builder or as a {@code @Bean RequestInterceptor} in Spring.
 *
 * <pre>{@code
 * Feign.builder()
 *     .requestInterceptor(new AllStakFeignInterceptor())
 *     .target(MyClient.class, "https://api.allstak.sa");
 * }</pre>
 *
 * <p>Spans + breadcrumbs aren't emitted here because Feign doesn't surface
 * the response on this hook — pair this with the OkHttp / Apache HttpClient
 * 5 module on the underlying transport for full span coverage.
 */
public final class AllStakFeignInterceptor implements RequestInterceptor {

    public static final String HEADER_TRACE_ID    = "x-allstak-trace-id";
    public static final String HEADER_SPAN_ID     = "x-allstak-span-id";
    public static final String HEADER_TRACEPARENT = "traceparent";

    @Override
    public void apply(RequestTemplate template) {
        AllStakClient client = AllStak.getClient();
        if (client == null) return;
        String url = template.feignTarget() != null ? template.feignTarget().url() + template.path() : template.path();
        if (!client.getConfig().getTracePropagationDecider().shouldPropagate(url)) return;

        String traceId = UUID.randomUUID().toString().replace("-", "");
        String spanId  = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        template.header(HEADER_TRACE_ID, traceId);
        template.header(HEADER_SPAN_ID, spanId);
        template.header(HEADER_TRACEPARENT, "00-" + traceId + "-" + spanId + "-" + client.traceparentSampledFlag());
    }
}
