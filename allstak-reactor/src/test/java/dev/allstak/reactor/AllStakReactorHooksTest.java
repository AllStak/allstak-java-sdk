package dev.allstak.reactor;

import dev.allstak.scope.Scopes;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AllStakReactorHooksTest {

    @BeforeEach
    void setUp() { AllStakReactorHooks.register(); }
    @AfterEach
    void tearDown() { AllStakReactorHooks.unregister(); Scopes.clear(); }

    @Test
    void scopePropagatesAcrossThreadHop() {
        Scopes.isolation().setTag("origin", "main-thread");
        AtomicReference<String> observed = new AtomicReference<>();

        Mono.fromCallable(() -> Scopes.isolation().getTags().get("origin"))
                .subscribeOn(Schedulers.parallel())
                .doOnNext(observed::set)
                .block(Duration.ofSeconds(2));

        assertThat(observed.get()).isEqualTo("main-thread");
    }

    @Test
    void register_isIdempotent() {
        // Registering twice must not throw or duplicate work.
        AllStakReactorHooks.register();
        AllStakReactorHooks.register();
    }
}
