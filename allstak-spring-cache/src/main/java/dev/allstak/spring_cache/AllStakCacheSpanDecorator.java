package dev.allstak.spring_cache;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import org.springframework.cache.Cache;

import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Decorates a Spring {@link Cache} so every get / put / evict emits an
 * AllStak span. Cache keys / values are NEVER captured — they often
 * carry PII.
 *
 * <p>Use directly:
 *
 * <pre>{@code
 * Cache wrapped = AllStakCacheSpanDecorator.wrap(originalCache);
 * }</pre>
 *
 * <p>or let the auto-configuration in {@code allstak-spring-boot-starter}
 * wrap every {@code @Bean Cache} when this module is on the classpath.
 */
public final class AllStakCacheSpanDecorator implements Cache {

    private final Cache delegate;

    public static Cache wrap(Cache delegate) { return new AllStakCacheSpanDecorator(delegate); }

    private AllStakCacheSpanDecorator(Cache delegate) { this.delegate = delegate; }

    @Override public String getName() { return delegate.getName(); }
    @Override public Object getNativeCache() { return delegate.getNativeCache(); }

    @Override
    public ValueWrapper get(Object key) {
        return span("cache.get", () -> delegate.get(key));
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        return span("cache.get", () -> delegate.get(key, type));
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        try {
            return span("cache.get_or_load", () -> delegate.get(key, valueLoader));
        } catch (Exception e) {
            throw e;
        }
    }

    @Override
    public void put(Object key, Object value) {
        span("cache.put", () -> { delegate.put(key, value); return null; });
    }

    @Override
    public void evict(Object key) {
        span("cache.evict", () -> { delegate.evict(key); return null; });
    }

    @Override
    public void clear() {
        span("cache.clear", () -> { delegate.clear(); return null; });
    }

    private <T> T span(String op, java.util.function.Supplier<T> work) {
        AllStakClient client = AllStak.getClient();
        long start = System.nanoTime();
        Throwable failure = null;
        T result = null;
        try {
            result = work.get();
            return result;
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            if (client != null) {
                long durMs = (System.nanoTime() - start) / 1_000_000L;
                try {
                    client.captureSpan(
                            UUID.randomUUID().toString().replace("-", ""),
                            UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                            null, "cache", op + " " + delegate.getName(),
                            failure == null ? "ok" : "error",
                            durMs, System.currentTimeMillis() - durMs, System.currentTimeMillis(),
                            "spring-cache", client.getConfig().getEnvironment(), null);
                } catch (Exception ignored) {}
            }
        }
    }
}
