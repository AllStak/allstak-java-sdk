package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.internal.SdkLogger;
import dev.allstak.model.RequestContext;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.retry.RetryContext;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetrySynchronizationManager;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class AllStakRetryPostProcessor implements BeanPostProcessor, Ordered {
    private final AllStakClient client;

    public AllStakRetryPostProcessor(AllStakClient client) {
        this.client = client;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!hasRetryableMethod(bean.getClass())) return bean;
        ProxyFactory proxyFactory = new ProxyFactory(bean);
        proxyFactory.setProxyTargetClass(true);
        MethodInterceptor interceptor = invocation -> {
            Method method = invocation.getMethod();
            Retryable retryable = retryable(method, bean.getClass());
            if (retryable == null) return invocation.proceed();
            long startMs = System.currentTimeMillis();
            try {
                Object result = invocation.proceed();
                captureRetry(method, beanName, retryable, "ok", startMs, null);
                return result;
            } catch (Throwable t) {
                captureRetry(method, beanName, retryable, "error", startMs, t);
                throw t;
            }
        };
        proxyFactory.addAdvice(interceptor);
        SdkLogger.debug("AllStak wrapped @Retryable bean '%s'", beanName);
        return proxyFactory.getProxy();
    }

    private void captureRetry(Method method, String beanName, Retryable retryable, String status, long startMs, Throwable error) {
        try {
            RetryContext context = RetrySynchronizationManager.getContext();
            int retryCount = context != null ? context.getRetryCount() : 0;
            String maxAttempts = retryable.maxAttemptsExpression() != null && !retryable.maxAttemptsExpression().isBlank()
                    ? retryable.maxAttemptsExpression()
                    : String.valueOf(retryable.maxAttempts());
            long endMs = System.currentTimeMillis();
            RequestContext req = AllStakClient.getRequestContext();
            String traceId = req != null && req.getTraceId() != null ? req.getTraceId() : UUID.randomUUID().toString();
            String requestId = req != null ? req.getRequestId() : null;
            Map<String, String> tags = new LinkedHashMap<>();
            tags.put("span.kind", "retry.attempt");
            tags.put("retry.framework", "spring-retry");
            tags.put("retry.method", method.getDeclaringClass().getName() + "." + method.getName());
            tags.put("retry.bean", beanName);
            tags.put("retry.attempt", String.valueOf(retryCount + 1));
            tags.put("retry.max_attempts", maxAttempts);
            tags.put("retry.final", String.valueOf("error".equals(status)));
            if (requestId != null) tags.put("request.id", requestId);
            if (error != null) {
                tags.put("error.type", error.getClass().getName());
                if (error.getMessage() != null) tags.put("error.message", error.getMessage());
            }
            client.captureSpan(traceId, randomSpanId(), null, "spring.retry", method.getName(), status,
                    endMs - startMs, startMs, endMs,
                    client.getConfig().getServiceName(), client.getConfig().getEnvironment(), tags);
        } catch (Exception ignored) {
            // Retry instrumentation must never affect customer retry behavior.
        }
    }

    private static boolean hasRetryableMethod(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (retryable(method, type) != null) return true;
        }
        return false;
    }

    private static Retryable retryable(Method method, Class<?> targetType) {
        Retryable retryable = method.getAnnotation(Retryable.class);
        if (retryable != null) return retryable;
        try {
            Method target = targetType.getMethod(method.getName(), method.getParameterTypes());
            return target.getAnnotation(Retryable.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static String randomSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 45;
    }
}
