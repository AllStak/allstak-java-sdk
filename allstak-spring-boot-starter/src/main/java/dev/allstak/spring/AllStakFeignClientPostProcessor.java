package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.internal.SdkLogger;
import dev.allstak.model.HttpRequestItem;
import dev.allstak.model.RequestContext;
import feign.Client;
import feign.Request;
import feign.Response;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public final class AllStakFeignClientPostProcessor implements BeanPostProcessor, Ordered {
    private final AllStakClient client;

    public AllStakFeignClientPostProcessor(AllStakClient client) {
        this.client = client;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof Client)) return bean;
        ProxyFactory proxyFactory = new ProxyFactory(bean);
        proxyFactory.setProxyTargetClass(true);
        MethodInterceptor interceptor = invocation -> {
            if (!"execute".equals(invocation.getMethod().getName()) || invocation.getArguments().length < 1
                    || !(invocation.getArguments()[0] instanceof Request request)) {
                return invocation.proceed();
            }
            long startMs = System.currentTimeMillis();
            try {
                Object result = invocation.proceed();
                if (result instanceof Response response) capture(request, response.status(), System.currentTimeMillis() - startMs, null);
                return result;
            } catch (Throwable t) {
                capture(request, 0, System.currentTimeMillis() - startMs, t);
                throw t;
            }
        };
        proxyFactory.addAdvice(interceptor);
        SdkLogger.debug("AllStak wrapped Feign Client bean '%s'", beanName);
        return proxyFactory.getProxy();
    }

    private void capture(Request request, int status, long durationMs, Throwable error) {
        try {
            RequestContext req = AllStakClient.getRequestContext();
            String traceId = firstHeader(request, AllStakFeignRequestInterceptor.HEADER_TRACE_ID);
            if (traceId == null && req != null) traceId = req.getTraceId();
            if (traceId == null) traceId = UUID.randomUUID().toString();
            String requestId = firstHeader(request, AllStakFeignRequestInterceptor.HEADER_REQUEST_ID);
            if (requestId == null && req != null) requestId = req.getRequestId();
            if (requestId == null) requestId = "feign-" + UUID.randomUUID();

            URI uri = URI.create(request.url());
            client.captureHttpRequest(HttpRequestItem.builder()
                    .traceId(traceId)
                    .requestId(requestId)
                    .spanId(UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                    .direction("outbound")
                    .method(request.httpMethod().name())
                    .host(uri.getHost())
                    .path(uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : ""))
                    .statusCode(status)
                    .durationMs(durationMs)
                    .timestamp(Instant.now().toString())
                    .requestBodyCaptureStatus("disabled")
                    .requestBodyCaptureReason("feign-body-capture-disabled")
                    .responseBodyCaptureStatus("disabled")
                    .responseBodyCaptureReason(error == null ? "feign-body-capture-disabled" : error.getClass().getName())
                    .environment(client.getConfig().getEnvironment())
                    .release(client.getConfig().getRelease())
                    .build());
        } catch (Exception ignored) {
            // Outbound Feign capture is fail-open.
        }
    }

    private static String firstHeader(Request request, String name) {
        var values = request.headers().get(name);
        return values == null || values.isEmpty() ? null : values.iterator().next();
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 45;
    }
}
