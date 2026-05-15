package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.internal.SdkLogger;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fail-open handler for uncaught exceptions from void-returning {@code @Async}
 * methods. It never suppresses or changes Spring's async behavior; it only
 * records the failure as best-effort telemetry.
 */
public final class AllStakAsyncExceptionHandler implements AsyncUncaughtExceptionHandler {

    private final AllStakClient client;

    public AllStakAsyncExceptionHandler(AllStakClient client) {
        this.client = client;
    }

    @Override
    public void handleUncaughtException(Throwable ex, Method method, Object... params) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("instrumentation", "spring.async");
            if (method != null) {
                metadata.put("async.method", method.getDeclaringClass().getName() + "." + method.getName());
                metadata.put("async.method.name", method.getName());
                metadata.put("async.class", method.getDeclaringClass().getName());
            }
            metadata.put("async.param.count", params != null ? params.length : 0);
            client.captureException(ex, "error", metadata);
        } catch (Exception captureFailure) {
            SdkLogger.debug("AllStak async exception capture failed: %s", captureFailure.getMessage());
        }
    }
}
