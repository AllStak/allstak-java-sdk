package dev.allstak.spring;

import dev.allstak.apache.AllStakApacheHttpInterceptor;
import dev.allstak.internal.SdkLogger;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/**
 * Automatically attaches the AllStak Apache HttpClient 5 request/response
 * interceptors to every {@link HttpClientBuilder} bean in the context.
 *
 * <p>Apache's classic client is built from a {@code HttpClientBuilder}; the
 * idiomatic Spring pattern is to expose that builder (or a
 * {@code CloseableHttpClient} produced from it) as a bean. When the builder
 * itself is a bean we attach the interceptor pair so every client it builds
 * is instrumented. The request interceptor is added <em>first</em> (so trace
 * headers are stamped before any user interceptor mutates the request) and
 * the response interceptor <em>last</em> (so it observes the final status).
 *
 * <p>A single {@link AllStakApacheHttpInterceptor} instance implements both
 * halves, mirroring the manual wiring documented on that class.
 */
public class AllStakApacheHttpClientPostProcessor implements BeanPostProcessor, Ordered {

    private final AllStakApacheHttpInterceptor interceptor;

    public AllStakApacheHttpClientPostProcessor(AllStakApacheHttpInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof HttpClientBuilder builder) {
            try {
                builder.addRequestInterceptorFirst(interceptor);
                builder.addResponseInterceptorLast(interceptor);
                SdkLogger.debug("AllStak attached Apache HttpClient5 interceptors to HttpClientBuilder bean '%s'", beanName);
            } catch (Exception e) {
                SdkLogger.debug("AllStak Apache HttpClient5 attach failed for bean '%s': %s", beanName, e.getMessage());
            }
        }
        return bean;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
