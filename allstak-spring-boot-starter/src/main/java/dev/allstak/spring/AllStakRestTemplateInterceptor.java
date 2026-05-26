package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.model.HttpRequestItem;
import dev.allstak.model.RequestContext;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.time.Instant;

/**
 * RestTemplate interceptor that captures outbound HTTP requests for AllStak monitoring.
 * Register this interceptor on your RestTemplate to automatically track outbound calls.
 */
public class AllStakRestTemplateInterceptor implements ClientHttpRequestInterceptor {

    private final AllStakClient client;

    public AllStakRestTemplateInterceptor(AllStakClient client) {
        this.client = client;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        long start = System.currentTimeMillis();
        String traceId = null;
        RequestContext ctx = AllStakClient.getRequestContext();
        if (ctx != null) {
            traceId = ctx.getTraceId();
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = AllStakTraceHeaders.randomTraceId();
        }
        String requestId = AllStakTraceHeaders.randomTraceId();
        String spanId = AllStakTraceHeaders.randomSpanId();
        AllStakTraceHeaders.apply(request.getHeaders(), traceId, requestId, spanId, client.traceparentSampledFlag());

        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException e) {
            // Record failed outbound request
            if (client.getConfig().isAutoBreadcrumbs()) {
                client.addBreadcrumb("http",
                        request.getMethod() + " " + request.getURI().getHost() + request.getURI().getPath() + " -> failed",
                        "error", null);
            }
            throw e;
        }

        long durationMs = System.currentTimeMillis() - start;
        HttpRequestItem item = HttpRequestItem.builder()
                .traceId(traceId)
                .requestId(requestId)
                .spanId(spanId)
                .direction("outbound")
                .method(request.getMethod().name())
                .host(request.getURI().getHost())
                .path(request.getURI().getPath())
                .statusCode(response.getStatusCode().value())
                .durationMs(durationMs)
                .requestSize(body != null ? body.length : 0)
                .responseSize(0) // not easily available without reading the stream
                .timestamp(Instant.now().toString())
                .environment(client.getConfig().getEnvironment())
                .release(client.getConfig().getRelease())
                .build();

        client.captureHttpRequest(item);

        return response;
    }
}
