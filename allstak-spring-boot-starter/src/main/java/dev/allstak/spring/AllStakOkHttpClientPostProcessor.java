package dev.allstak.spring;

import dev.allstak.internal.SdkLogger;
import dev.allstak.okhttp.AllStakOkHttpInterceptor;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/**
 * Automatically attaches {@link AllStakOkHttpInterceptor} to every
 * {@link OkHttpClient} bean in the application context.
 *
 * <p>{@code OkHttpClient} is immutable, so this post-processor rebuilds the
 * bean through {@code newBuilder().addInterceptor(...).build()} — the
 * resulting client shares the original's connection pool and dispatcher
 * (OkHttp's {@code newBuilder()} copies them by reference), so no resources
 * are duplicated. The interceptor is added only when one is not already
 * present, so the same OkHttp call never emits two AllStak spans.
 *
 * <p>This mirrors {@link AllStakRestTemplatePostProcessor}: the developer
 * registers a plain {@code OkHttpClient} bean and gets outbound HTTP capture
 * with no per-call code.
 */
public class AllStakOkHttpClientPostProcessor implements BeanPostProcessor, Ordered {

    private final AllStakOkHttpInterceptor interceptor;

    public AllStakOkHttpClientPostProcessor(AllStakOkHttpInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof OkHttpClient client) {
            for (Interceptor existing : client.interceptors()) {
                if (existing instanceof AllStakOkHttpInterceptor) {
                    return bean; // already attached — never double-instrument
                }
            }
            OkHttpClient instrumented = client.newBuilder().addInterceptor(interceptor).build();
            SdkLogger.debug("AllStak attached OkHttp interceptor to OkHttpClient bean '%s'", beanName);
            return instrumented;
        }
        return bean;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
