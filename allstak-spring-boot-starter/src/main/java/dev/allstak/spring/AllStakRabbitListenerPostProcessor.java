package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.internal.SdkLogger;
import dev.allstak.model.RequestContext;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wraps methods annotated with {@link RabbitListener} to record message
 * consumer spans and exact listener exceptions. The original exception is
 * rethrown so Spring AMQP retry/DLQ behavior remains unchanged.
 */
public final class AllStakRabbitListenerPostProcessor implements BeanPostProcessor, Ordered {

    private final AllStakClient client;

    public AllStakRabbitListenerPostProcessor(AllStakClient client) {
        this.client = client;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean == null) return null;
        Class<?> targetClass = ClassUtils.getUserClass(bean);
        if (!hasRabbitListener(targetClass)) return bean;

        ProxyFactory proxyFactory = new ProxyFactory(bean);
        proxyFactory.setProxyTargetClass(true);
        StaticMethodMatcherPointcut pointcut = new StaticMethodMatcherPointcut() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                Method target = findMethodOnTarget(method, targetClass);
                return target != null && target.isAnnotationPresent(RabbitListener.class);
            }
        };
        MethodInterceptor interceptor = this::invokeListener;
        proxyFactory.addAdvisor(new DefaultPointcutAdvisor(pointcut, interceptor));
        SdkLogger.debug("AllStak wrapped @RabbitListener methods on '%s'", beanName);
        return proxyFactory.getProxy();
    }

    private Object invokeListener(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Object target = invocation.getThis();
        Method targetMethod = findMethodOnTarget(method, target != null ? target.getClass() : method.getDeclaringClass());
        RabbitListener annotation = targetMethod != null
                ? targetMethod.getAnnotation(RabbitListener.class)
                : method.getAnnotation(RabbitListener.class);
        Message message = findMessage(invocation.getArguments());
        String queue = annotation != null && annotation.queues().length > 0 ? annotation.queues()[0] : null;
        AllStakRabbitSupport.RabbitContext ctx = AllStakRabbitSupport.fromMessage(message, queue);
        RequestContext previous = AllStakClient.getRequestContext();
        long startMs = System.currentTimeMillis();
        AllStakClient.setRequestContext(AllStakRabbitSupport.requestContext(ctx));
        try {
            Object result = invocation.proceed();
            AllStakRabbitSupport.captureSpan(client, ctx, "messaging.consumer",
                    method.getDeclaringClass().getSimpleName() + "." + method.getName(),
                    "ok", startMs, null);
            return result;
        } catch (Throwable t) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("instrumentation", "spring.rabbit.listener");
            metadata.put("messaging.system", "rabbitmq");
            metadata.put("messaging.destination", ctx.queue());
            metadata.put("messaging.rabbitmq.exchange", ctx.exchange());
            metadata.put("messaging.rabbitmq.routing_key", ctx.routingKey());
            metadata.put("messaging.rabbitmq.redelivered", ctx.redelivered());
            metadata.put("messaging.rabbitmq.delivery_tag", ctx.deliveryTag());
            metadata.put("messaging.rabbitmq.x_death_count", ctx.xDeathCount());
            metadata.put("messaging.rabbitmq.dead_letter_exchange", ctx.deadLetterExchange());
            metadata.put("messaging.rabbitmq.dead_letter_routing_key", ctx.deadLetterRoutingKey());
            metadata.put("requestId", ctx.requestId());
            client.captureException(t, "error", metadata);
            AllStakRabbitSupport.captureSpan(client, ctx, "messaging.consumer",
                    method.getDeclaringClass().getSimpleName() + "." + method.getName(),
                    "error", startMs, t);
            throw t;
        } finally {
            if (previous != null) AllStakClient.setRequestContext(previous);
            else AllStakClient.clearRequestContext();
        }
    }

    private static Message findMessage(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof Message message) return message;
        }
        return null;
    }

    private static boolean hasRabbitListener(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.isAnnotationPresent(RabbitListener.class)) return true;
        }
        return false;
    }

    private static Method findMethodOnTarget(Method method, Class<?> targetClass) {
        Class<?> userClass = ClassUtils.getUserClass(targetClass);
        try {
            return userClass.getMethod(method.getName(), method.getParameterTypes());
        } catch (NoSuchMethodException ignored) {
            for (Method candidate : userClass.getDeclaredMethods()) {
                if (candidate.getName().equals(method.getName()) && candidate.getParameterCount() == method.getParameterCount()) {
                    return candidate;
                }
            }
            return null;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 60;
    }
}
