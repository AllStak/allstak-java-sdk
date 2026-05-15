package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.internal.SdkLogger;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/**
 * Adds Rabbit producer trace headers and producer spans to RabbitTemplate
 * sends. Failures are observed and rethrown so application publish behavior is
 * unchanged.
 */
public final class AllStakRabbitTemplatePostProcessor implements BeanPostProcessor, Ordered {

    private final AllStakClient client;

    public AllStakRabbitTemplatePostProcessor(AllStakClient client) {
        this.client = client;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof RabbitTemplate template)) return bean;
        template.addBeforePublishPostProcessors(message -> {
            try {
                AllStakRabbitSupport.injectProducerHeaders(message);
            } catch (Exception ignored) {
                // Header injection must never break publishing.
            }
            return message;
        });

        ProxyFactory proxyFactory = new ProxyFactory(bean);
        proxyFactory.setProxyTargetClass(true);
        MethodInterceptor interceptor = invocation -> {
            String name = invocation.getMethod().getName();
            if (!isSendMethod(name)) return invocation.proceed();
            Message message = findMessage(invocation.getArguments());
            AllStakRabbitSupport.RabbitContext ctx = message != null
                    ? AllStakRabbitSupport.injectProducerHeaders(message)
                    : AllStakRabbitSupport.fromMessage(null, null);
            long startMs = System.currentTimeMillis();
            try {
                Object result = invocation.proceed();
                AllStakRabbitSupport.captureSpan(client, ctx, "messaging.producer", name, "ok", startMs, null);
                return result;
            } catch (Throwable t) {
                client.captureException(t, "error", java.util.Map.of(
                        "instrumentation", "spring.rabbit.template",
                        "messaging.system", "rabbitmq",
                        "requestId", ctx.requestId()));
                AllStakRabbitSupport.captureSpan(client, ctx, "messaging.producer", name, "error", startMs, t);
                throw t;
            }
        };
        proxyFactory.addAdvice(interceptor);
        SdkLogger.debug("AllStak wrapped RabbitTemplate bean '%s'", beanName);
        return proxyFactory.getProxy();
    }

    private static boolean isSendMethod(String name) {
        return name.equals("send") || name.equals("convertAndSend");
    }

    private static Message findMessage(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof Message message) return message;
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 70;
    }
}
