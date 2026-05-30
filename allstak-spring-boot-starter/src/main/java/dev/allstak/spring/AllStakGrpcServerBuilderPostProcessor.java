package dev.allstak.spring;

import dev.allstak.grpc.AllStakGrpcServerInterceptor;
import dev.allstak.internal.SdkLogger;
import io.grpc.ServerBuilder;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/**
 * Automatically registers {@link AllStakGrpcServerInterceptor} on every
 * {@link ServerBuilder} bean in the application context, so an inbound gRPC
 * call's trace context is copied into the active scope without the developer
 * calling {@code .intercept(...)} themselves.
 *
 * <p>Spring apps that build a gRPC {@code Server} typically expose the
 * {@code ServerBuilder} as a bean (e.g. {@code ServerBuilder.forPort(...)})
 * and call {@code .build().start()} in a lifecycle bean. Attaching here means
 * every server built from that builder is instrumented automatically.
 */
public class AllStakGrpcServerBuilderPostProcessor implements BeanPostProcessor, Ordered {

    private final AllStakGrpcServerInterceptor interceptor;

    public AllStakGrpcServerBuilderPostProcessor(AllStakGrpcServerInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof ServerBuilder<?> builder) {
            try {
                builder.intercept(interceptor);
                SdkLogger.debug("AllStak attached gRPC server interceptor to ServerBuilder bean '%s'", beanName);
            } catch (Exception e) {
                SdkLogger.debug("AllStak gRPC interceptor attach failed for bean '%s': %s", beanName, e.getMessage());
            }
        }
        return bean;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
