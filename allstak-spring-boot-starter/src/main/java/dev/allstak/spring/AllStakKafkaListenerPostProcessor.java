package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.internal.SdkLogger;
import dev.allstak.model.RequestContext;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;

public final class AllStakKafkaListenerPostProcessor implements BeanPostProcessor, Ordered {
    private final AllStakClient client;

    public AllStakKafkaListenerPostProcessor(AllStakClient client) {
        this.client = client;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean == null) return null;
        Class<?> targetClass = ClassUtils.getUserClass(bean);
        if (!hasKafkaListener(targetClass)) return bean;

        ProxyFactory proxyFactory = new ProxyFactory(bean);
        proxyFactory.setProxyTargetClass(true);
        StaticMethodMatcherPointcut pointcut = new StaticMethodMatcherPointcut() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                Method target = findMethodOnTarget(method, targetClass);
                return target != null && target.isAnnotationPresent(KafkaListener.class);
            }
        };
        MethodInterceptor interceptor = this::invokeListener;
        proxyFactory.addAdvisor(new DefaultPointcutAdvisor(pointcut, interceptor));
        SdkLogger.debug("AllStak wrapped @KafkaListener methods on '%s'", beanName);
        return proxyFactory.getProxy();
    }

    private Object invokeListener(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Method targetMethod = findMethodOnTarget(method, invocation.getThis() != null ? invocation.getThis().getClass() : method.getDeclaringClass());
        KafkaListener annotation = targetMethod != null
                ? targetMethod.getAnnotation(KafkaListener.class)
                : method.getAnnotation(KafkaListener.class);
        ConsumerRecord<?, ?> record = findRecord(invocation.getArguments());
        String groupId = annotation != null && !annotation.groupId().isBlank() ? annotation.groupId() : null;
        AllStakKafkaSupport.KafkaContext ctx = AllStakKafkaSupport.fromRecord(record, groupId);
        RequestContext previous = AllStakClient.getRequestContext();
        long startMs = System.currentTimeMillis();
        AllStakClient.setRequestContext(AllStakKafkaSupport.requestContext(ctx));
        try {
            Object result = invocation.proceed();
            AllStakKafkaSupport.captureSpan(client, ctx, "messaging.consumer",
                    method.getDeclaringClass().getSimpleName() + "." + method.getName(),
                    "ok", startMs, null);
            return result;
        } catch (Throwable t) {
            client.captureException(t, "error", AllStakKafkaSupport.metadata(ctx, "spring.kafka.listener"));
            AllStakKafkaSupport.captureSpan(client, ctx, "messaging.consumer",
                    method.getDeclaringClass().getSimpleName() + "." + method.getName(),
                    "error", startMs, t);
            throw t;
        } finally {
            if (previous != null) AllStakClient.setRequestContext(previous);
            else AllStakClient.clearRequestContext();
        }
    }

    private static ConsumerRecord<?, ?> findRecord(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof ConsumerRecord<?, ?> record) return record;
        }
        return null;
    }

    private static boolean hasKafkaListener(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.isAnnotationPresent(KafkaListener.class)) return true;
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
