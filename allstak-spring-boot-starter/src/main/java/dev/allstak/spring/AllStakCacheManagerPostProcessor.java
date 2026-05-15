package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.internal.SdkLogger;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.Ordered;
import org.springframework.lang.Nullable;

import java.util.Collection;
import java.util.concurrent.Callable;

public final class AllStakCacheManagerPostProcessor implements BeanPostProcessor, Ordered {
    private final AllStakClient client;

    public AllStakCacheManagerPostProcessor(AllStakClient client) {
        this.client = client;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof CacheManager manager) || bean instanceof InstrumentedCacheManager) return bean;
        SdkLogger.debug("AllStak wrapped CacheManager bean '%s'", beanName);
        return new InstrumentedCacheManager(manager, client);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 50;
    }

    private record InstrumentedCacheManager(CacheManager delegate, AllStakClient client) implements CacheManager {
        @Override
        public Cache getCache(String name) {
            Cache cache = delegate.getCache(name);
            return cache == null || cache instanceof InstrumentedCache ? cache : new InstrumentedCache(cache, client);
        }

        @Override
        public Collection<String> getCacheNames() {
            return delegate.getCacheNames();
        }
    }

    private record InstrumentedCache(Cache delegate, AllStakClient client) implements Cache {
        @Override
        public String getName() { return delegate.getName(); }

        @Override
        public Object getNativeCache() { return delegate.getNativeCache(); }

        @Override
        @Nullable
        public ValueWrapper get(Object key) {
            return capture("get", key, () -> delegate.get(key));
        }

        @Override
        @Nullable
        public <T> T get(Object key, @Nullable Class<T> type) {
            return capture("get", key, () -> delegate.get(key, type));
        }

        @Override
        @Nullable
        public <T> T get(Object key, Callable<T> valueLoader) {
            return capture("get", key, () -> delegate.get(key, valueLoader));
        }

        @Override
        public void put(Object key, @Nullable Object value) {
            captureVoid("put", key, () -> delegate.put(key, value));
        }

        @Override
        @Nullable
        public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
            return capture("putIfAbsent", key, () -> delegate.putIfAbsent(key, value));
        }

        @Override
        public void evict(Object key) {
            captureVoid("evict", key, () -> delegate.evict(key));
        }

        @Override
        public boolean evictIfPresent(Object key) {
            return capture("evict", key, () -> delegate.evictIfPresent(key));
        }

        @Override
        public void clear() {
            captureVoid("clear", null, delegate::clear);
        }

        @Override
        public boolean invalidate() {
            return capture("clear", null, delegate::invalidate);
        }

        private <T> T capture(String operation, Object key, ThrowingSupplier<T> supplier) {
            long startMs = System.currentTimeMillis();
            try {
                T result = supplier.get();
                AllStakCacheSupport.captureCacheSpan(client, operation, delegate.getName(), key, startMs, null);
                return result;
            } catch (RuntimeException e) {
                AllStakCacheSupport.captureCacheSpan(client, operation, delegate.getName(), key, startMs, e);
                throw e;
            }
        }

        private void captureVoid(String operation, Object key, ThrowingRunnable runnable) {
            long startMs = System.currentTimeMillis();
            try {
                runnable.run();
                AllStakCacheSupport.captureCacheSpan(client, operation, delegate.getName(), key, startMs, null);
            } catch (RuntimeException e) {
                AllStakCacheSupport.captureCacheSpan(client, operation, delegate.getName(), key, startMs, e);
                throw e;
            }
        }
    }

    private interface ThrowingSupplier<T> {
        T get();
    }

    private interface ThrowingRunnable {
        void run();
    }
}
