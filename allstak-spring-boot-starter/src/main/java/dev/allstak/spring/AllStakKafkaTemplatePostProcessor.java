package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.internal.SdkLogger;
import org.aopalliance.intercept.MethodInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.Message;

public final class AllStakKafkaTemplatePostProcessor implements BeanPostProcessor, Ordered {
    private final AllStakClient client;

    public AllStakKafkaTemplatePostProcessor(AllStakClient client) {
        this.client = client;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof KafkaTemplate<?, ?>)) return bean;
        ProxyFactory proxyFactory = new ProxyFactory(bean);
        proxyFactory.setProxyTargetClass(true);
        MethodInterceptor interceptor = invocation -> {
            if (!"send".equals(invocation.getMethod().getName())) return invocation.proceed();
            ProducerRecord<?, ?> record = findRecord(invocation.getArguments());
            AllStakKafkaSupport.KafkaContext ctx = record != null
                    ? AllStakKafkaSupport.injectProducerHeaders(record)
                    : AllStakKafkaSupport.fromSendArguments(invocation.getArguments());
            injectMessageHeaders(invocation.getArguments(), ctx);
            long startMs = System.currentTimeMillis();
            try {
                Object result = invocation.proceed();
                AllStakKafkaSupport.captureSpan(client, ctx, "messaging.producer", "KafkaTemplate.send", "ok", startMs, null);
                return result;
            } catch (Throwable t) {
                client.captureException(t, "error", AllStakKafkaSupport.metadata(ctx, "spring.kafka.template"));
                AllStakKafkaSupport.captureSpan(client, ctx, "messaging.producer", "KafkaTemplate.send", "error", startMs, t);
                throw t;
            }
        };
        proxyFactory.addAdvice(interceptor);
        SdkLogger.debug("AllStak wrapped KafkaTemplate bean '%s'", beanName);
        return proxyFactory.getProxy();
    }

    private static ProducerRecord<?, ?> findRecord(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof ProducerRecord<?, ?> record) return record;
        }
        return null;
    }

    private static void injectMessageHeaders(Object[] args, AllStakKafkaSupport.KafkaContext ctx) {
        if (args == null) return;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Message<?> message) {
                args[i] = AllStakKafkaSupport.injectMessageHeaders(message, ctx);
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 70;
    }
}
