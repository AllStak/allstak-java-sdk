package dev.allstak.spring_cache;

import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCache;

import static org.assertj.core.api.Assertions.assertThat;

class AllStakCacheSpanDecoratorTest {

    @Test
    void delegatesGetPutEvict() {
        var underlying = new ConcurrentMapCache("orders");
        var wrapped = AllStakCacheSpanDecorator.wrap(underlying);

        wrapped.put("k1", "v1");
        assertThat(wrapped.get("k1").get()).isEqualTo("v1");

        wrapped.evict("k1");
        assertThat(wrapped.get("k1")).isNull();

        assertThat(wrapped.getName()).isEqualTo("orders");
    }
}
