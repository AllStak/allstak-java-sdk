package dev.allstak.lettuce;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;

class AllStakLettuceTracingTest {

    @AfterEach
    void tearDown() { AllStak.reset(); }

    @Test
    void enabled_followsSdkInit() {
        var tracing = AllStakLettuceTracing.create();
        assertThat(tracing.isEnabled()).isFalse();

        AllStakConfig cfg = AllStakConfig.builder()
                .apiKey("ask_live_test").environment("test").release("v0.0.1")
                .enableAutoSessionTracking(false).installUncaughtExceptionHandler(false).build();
        AllStak.init(new AllStakClient(cfg, new HttpTransport("http://127.0.0.1:1", cfg.getApiKey())));

        assertThat(tracing.isEnabled()).isTrue();
        assertThat(tracing.includeCommandArgsInSpanTags()).isFalse();
    }
}
