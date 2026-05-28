package dev.allstak.feign;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.transport.HttpTransport;
import feign.RequestTemplate;
import feign.Target;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AllStakFeignInterceptorTest {

    @AfterEach
    void tearDown() { AllStak.reset(); }

    private void initSdk(List<String> targets) {
        AllStakConfig cfg = AllStakConfig.builder()
                .apiKey("ask_live_test").environment("test").release("v0.0.1")
                .enableAutoSessionTracking(false).installUncaughtExceptionHandler(false)
                .tracePropagationTargets(targets).build();
        AllStak.init(new AllStakClient(cfg, new HttpTransport("http://127.0.0.1:1", cfg.getApiKey())));
    }

    @Test
    void injectsHeaders_whenTargetMatches() {
        initSdk(List.of("api.allstak.sa"));
        RequestTemplate t = new RequestTemplate();
        t.feignTarget(new Target.HardCodedTarget<>(Object.class, "MyClient", "https://api.allstak.sa"));
        t.uri("/orders/42");

        new AllStakFeignInterceptor().apply(t);

        assertThat(t.headers().get(AllStakFeignInterceptor.HEADER_TRACE_ID)).isNotEmpty();
        assertThat(t.headers().get(AllStakFeignInterceptor.HEADER_TRACEPARENT).iterator().next()).startsWith("00-");
    }

    @Test
    void omitsHeaders_whenTargetNotOnAllowlist() {
        initSdk(List.of("api.allstak.sa"));
        RequestTemplate t = new RequestTemplate();
        t.feignTarget(new Target.HardCodedTarget<>(Object.class, "Third", "https://random.example.com"));
        t.uri("/x");

        new AllStakFeignInterceptor().apply(t);
        assertThat(t.headers().get(AllStakFeignInterceptor.HEADER_TRACE_ID)).isNullOrEmpty();
    }
}
