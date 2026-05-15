package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.internal.SdkLogger;
import dev.allstak.model.JobHandle;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;

/**
 * Automatically wraps every Spring bean method annotated with
 * {@link org.springframework.scheduling.annotation.Scheduled} so each
 * execution emits an AllStak cron heartbeat without requiring the user to
 * write {@code AllStak.startJob(...)} / {@code finishJob(...)} boilerplate.
 *
 * <p>The monitor slug is derived from the bean class simple name + method name
 * (e.g. {@code reportTask.generateDailyReport}). Users can override this by
 * calling {@link AllStak#startJob(String)} explicitly — the automatic wrapper
 * detects any existing cron pings on the same slug within the method and
 * skips its own ping to prevent duplicates. (Stronger: the wrapper records a
 * ThreadLocal marker while running, and the manual helpers read it to stay
 * silent when wrapped automatically.)
 *
 * <p>Instrumentation is applied via Spring AOP proxies, not bytecode
 * manipulation, to keep the SDK portable. Beans that are already proxied keep
 * their existing advisors; we only add ours. Beans that use {@code @Scheduled}
 * on {@code final} classes with no interfaces will not be proxied and will
 * fall through silently (Spring itself has the same limitation for AOP).
 *
 * <p>On success the heartbeat is {@code status="success"} with the measured
 * duration. On any thrown exception the heartbeat is {@code status="failed"}
 * with the exception message; the exception is always re-thrown so the
 * scheduler's retry/error handling still runs.
 */
public class AllStakScheduledPostProcessor implements BeanPostProcessor, Ordered {

    /**
     * Marker set by the auto-wrapper while a scheduled method is running, so
     * manually placed facade job helpers inside the same method do not create
     * duplicate heartbeats.
     */
    static final ThreadLocal<Boolean> INSIDE_AUTO_JOB = ThreadLocal.withInitial(() -> false);

    private final AllStakClient client;

    public AllStakScheduledPostProcessor(AllStakClient client) {
        this.client = client;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean == null) return null;
        Class<?> targetClass = ClassUtils.getUserClass(bean);
        if (!hasScheduledMethod(targetClass)) {
            return bean;
        }

        ProxyFactory proxyFactory = new ProxyFactory(bean);
        proxyFactory.setProxyTargetClass(true); // CGLIB, works for classes without interfaces

        StaticMethodMatcherPointcut pointcut = new StaticMethodMatcherPointcut() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                Method target = findMethodOnTarget(method, targetClass);
                return target != null && target.isAnnotationPresent(Scheduled.class);
            }
        };

        MethodInterceptor interceptor = (MethodInvocation invocation) -> {
            Method method = invocation.getMethod();
            String slug = deriveSlug(targetClass, method);
            return runWithHeartbeat(slug, invocation);
        };

        proxyFactory.addAdvisor(new DefaultPointcutAdvisor(pointcut, interceptor));
        SdkLogger.debug("AllStak wrapped @Scheduled methods on '%s'", beanName);
        return proxyFactory.getProxy();
    }

    private Object runWithHeartbeat(String slug, MethodInvocation invocation) throws Throwable {
        JobHandle handle = client.startJob(slug);
        boolean previous = INSIDE_AUTO_JOB.get();
        INSIDE_AUTO_JOB.set(true);
        try {
            Object result = invocation.proceed();
            try {
                client.finishJob(handle, "success");
            } catch (Exception ignored) { /* never raise into host */ }
            return result;
        } catch (Throwable t) {
            try {
                client.finishJob(handle, "failed", safeMessage(t));
            } catch (Exception ignored) { /* never raise into host */ }
            throw t;
        } finally {
            INSIDE_AUTO_JOB.set(previous);
        }
    }

    private static boolean hasScheduledMethod(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Scheduled.class)) return true;
        }
        return false;
    }

    private static Method findMethodOnTarget(Method method, Class<?> targetClass) {
        try {
            return targetClass.getMethod(method.getName(), method.getParameterTypes());
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * Derives a cron-monitor slug from the bean class + method name in the
     * format required by the AllStak backend: lowercase alphanumeric with
     * hyphens. {@code ReportTask.generateDailyReport} becomes
     * {@code report-task-generate-daily-report}.
     */
    static String deriveSlug(Class<?> cls, Method method) {
        String raw = cls.getSimpleName() + "-" + method.getName();
        StringBuilder out = new StringBuilder(raw.length() + 8);
        char prev = '-';
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && prev != '-') out.append('-');
                out.append(Character.toLowerCase(c));
                prev = Character.toLowerCase(c);
            } else if (Character.isLetterOrDigit(c)) {
                out.append(c);
                prev = c;
            } else {
                if (prev != '-') out.append('-');
                prev = '-';
            }
        }
        int start = 0, end = out.length();
        while (start < end && out.charAt(start) == '-') start++;
        while (end > start && out.charAt(end - 1) == '-') end--;
        return out.substring(start, end);
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return null;
        String msg = t.getMessage();
        return msg != null ? msg : t.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        // Run after typical post-processors so other proxies (tx, security) are applied first.
        return Ordered.LOWEST_PRECEDENCE - 50;
    }
}
