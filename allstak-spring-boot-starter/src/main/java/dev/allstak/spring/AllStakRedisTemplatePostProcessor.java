package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.internal.SdkLogger;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Set;

public final class AllStakRedisTemplatePostProcessor implements BeanPostProcessor, Ordered {
    private static final Set<String> OPERATIONS = Set.of("delete", "hasKey", "expire", "persist", "rename", "execute");
    private static final Set<String> HELPER_FACTORIES = Set.of("opsForValue", "opsForHash", "opsForList", "opsForSet", "opsForZSet");
    private static final Set<String> VALUE_OPERATIONS = Set.of("get", "set", "setIfAbsent", "setIfPresent", "increment", "decrement", "append", "getAndSet");
    private static final Set<String> HASH_OPERATIONS = Set.of("get", "put", "putAll", "putIfAbsent", "delete", "hasKey", "entries", "values", "keys");
    private static final Set<String> LIST_OPERATIONS = Set.of("leftPush", "rightPush", "leftPop", "rightPop", "range", "remove", "size", "trim");
    private static final Set<String> SET_OPERATIONS = Set.of("add", "remove", "isMember", "members", "size", "pop");
    private static final Set<String> ZSET_OPERATIONS = Set.of("add", "remove", "range", "score", "rank", "zCard", "incrementScore");

    private final AllStakClient client;

    public AllStakRedisTemplatePostProcessor(AllStakClient client) {
        this.client = client;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof RedisTemplate<?, ?>)) return bean;
        ProxyFactory proxyFactory = new ProxyFactory(bean);
        proxyFactory.setProxyTargetClass(true);
        MethodInterceptor interceptor = invocation -> {
            String operation = invocation.getMethod().getName();
            if (HELPER_FACTORIES.contains(operation)) {
                Object operations = invocation.proceed();
                return wrapOperations(operations, operation);
            }
            if (!OPERATIONS.contains(operation)) return invocation.proceed();
            Object key = invocation.getArguments() != null && invocation.getArguments().length > 0
                    ? invocation.getArguments()[0]
                    : null;
            long startMs = System.currentTimeMillis();
            try {
                Object result = invocation.proceed();
                AllStakCacheSupport.captureCacheSpan(client, "redis." + operation, "redis", key, startMs, null);
                return result;
            } catch (Throwable t) {
                AllStakCacheSupport.captureCacheSpan(client, "redis." + operation, "redis", key, startMs, t);
                throw t;
            }
        };
        proxyFactory.addAdvice(interceptor);
        SdkLogger.debug("AllStak wrapped RedisTemplate bean '%s'", beanName);
        return proxyFactory.getProxy();
    }

    private Object wrapOperations(Object operations, String factoryName) {
        if (operations == null) return null;
        ProxyFactory proxyFactory = new ProxyFactory(operations);
        proxyFactory.setProxyTargetClass(false);
        MethodInterceptor interceptor = invocation -> {
            String method = invocation.getMethod().getName();
            String family = family(factoryName);
            if (!isCapturedOperation(factoryName, method)) return invocation.proceed();
            Object key = firstKey(invocation.getArguments());
            long startMs = System.currentTimeMillis();
            try {
                Object result = invocation.proceed();
                AllStakCacheSupport.captureCacheSpan(client, "redis." + family + "." + method, "redis", key, startMs, null);
                return result;
            } catch (Throwable t) {
                AllStakCacheSupport.captureCacheSpan(client, "redis." + family + "." + method, "redis", key, startMs, t);
                throw t;
            }
        };
        proxyFactory.addAdvice(interceptor);
        return proxyFactory.getProxy();
    }

    private static boolean isCapturedOperation(String factoryName, String method) {
        return switch (factoryName) {
            case "opsForValue" -> VALUE_OPERATIONS.contains(method);
            case "opsForHash" -> HASH_OPERATIONS.contains(method);
            case "opsForList" -> LIST_OPERATIONS.contains(method);
            case "opsForSet" -> SET_OPERATIONS.contains(method);
            case "opsForZSet" -> ZSET_OPERATIONS.contains(method);
            default -> false;
        };
    }

    private static String family(String factoryName) {
        return switch (factoryName) {
            case "opsForValue" -> "value";
            case "opsForHash" -> "hash";
            case "opsForList" -> "list";
            case "opsForSet" -> "set";
            case "opsForZSet" -> "zset";
            default -> "operation";
        };
    }

    private static Object firstKey(Object[] args) {
        return args != null && args.length > 0 ? args[0] : null;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 55;
    }
}
