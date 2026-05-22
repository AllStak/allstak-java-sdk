package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.internal.SdkLogger;
import dev.allstak.model.HttpRequestItem;
import dev.allstak.model.RequestContext;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;

/**
 * Reactive {@link ExchangeFilterFunction} that captures every outbound
 * {@link org.springframework.web.reactive.function.client.WebClient} call as
 * AllStak telemetry.
 *
 * <p>Records the request on both success and error paths. Trace ID is
 * inherited from the current inbound request context ({@link AllStakClient#getRequestContext()})
 * when available, otherwise a fresh one is generated so the outbound call
 * is still grouped correctly on the dashboard.
 *
 * <p>Failures are reported with an empty status (0) and the exception class
 * name as the error fingerprint. The error is always re-raised so user
 * pipelines and fallback logic continue to work as expected.
 */
public class AllStakWebClientFilter implements ExchangeFilterFunction {

    private final AllStakClient client;

    public AllStakWebClientFilter(AllStakClient client) {
        this.client = client;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        final long start = System.currentTimeMillis();
        final String traceId = resolveTraceId();
        final String requestId = AllStakTraceHeaders.randomTraceId();
        final String spanId = AllStakTraceHeaders.randomSpanId();
        final URI uri = request.url();
        ClientRequest tracedRequest = ClientRequest.from(request)
                .headers(h -> AllStakTraceHeaders.apply(h, traceId, requestId, spanId))
                .build();

        return next.exchange(tracedRequest)
                .doOnNext(response -> safeRecord(tracedRequest, uri, traceId, requestId, spanId, start,
                        response.statusCode().value(), null))
                .doOnError(err -> safeRecord(tracedRequest, uri, traceId, requestId, spanId, start, 0,
                        err.getClass().getName()));
    }

    private void safeRecord(ClientRequest request, URI uri, String traceId, String requestId, String spanId,
                            long start, int statusCode, String errorFingerprint) {
        try {
            long durationMs = System.currentTimeMillis() - start;
            HttpRequestItem item = HttpRequestItem.builder()
                    .traceId(traceId)
                    .requestId(requestId)
                    .spanId(spanId)
                    .direction("outbound")
                    .method(request.method().name())
                    .host(uri.getHost() != null ? uri.getHost() : "")
                    .path(uri.getPath() != null ? uri.getPath() : "/")
                    .statusCode(statusCode)
                    .durationMs(durationMs)
                    .requestSize(0)
                    .responseSize(0)
                    .errorFingerprint(errorFingerprint)
                    .timestamp(Instant.now().toString())
                    .environment(client.getConfig().getEnvironment())
                    .release(client.getConfig().getRelease())
                    .build();
            client.captureHttpRequest(item);
        } catch (Exception swallowed) {
            SdkLogger.debug("AllStak WebClient filter record failed: %s", swallowed.getMessage());
        }
    }

    private String resolveTraceId() {
        RequestContext ctx = AllStakClient.getRequestContext();
        if (ctx != null && ctx.getTraceId() != null && !ctx.getTraceId().isEmpty()) {
            return ctx.getTraceId();
        }
        return AllStakTraceHeaders.randomTraceId();
    }
}
