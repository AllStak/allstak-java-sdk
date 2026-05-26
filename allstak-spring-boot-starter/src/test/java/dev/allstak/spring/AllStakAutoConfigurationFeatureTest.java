package dev.allstak.spring;

import dev.allstak.AllStakConfig;
import dev.allstak.model.ErrorEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Spring auto-configuration wires the new uncaught-handler opt-out,
 * sampleRate / tracesSampleRate properties, and an optional {@link AllStakBeforeSend}
 * bean into {@link AllStakConfig}.
 */
class AllStakAutoConfigurationFeatureTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AllStakAutoConfiguration.class))
            .withPropertyValues("allstak.api-key=ask_test", "allstak.debug=true");

    @Test
    void defaultsCarryThroughToConfig() {
        runner.run(ctx -> {
            AllStakConfig config = ctx.getBean(AllStakConfig.class);
            assertThat(config.isInstallUncaughtExceptionHandler()).isTrue();
            assertThat(config.getSampleRate()).isEqualTo(1.0);
            assertThat(config.getTracesSampleRate()).isNull();
            assertThat(config.getBeforeSend()).isNull();
        });
    }

    @Test
    void propertiesOverrideConfig() {
        runner.withPropertyValues(
                "allstak.install-uncaught-exception-handler=false",
                "allstak.sample-rate=0.25",
                "allstak.traces-sample-rate=0.5"
        ).run(ctx -> {
            AllStakConfig config = ctx.getBean(AllStakConfig.class);
            assertThat(config.isInstallUncaughtExceptionHandler()).isFalse();
            assertThat(config.getSampleRate()).isEqualTo(0.25);
            assertThat(config.getTracesSampleRate()).isEqualTo(0.5);
        });
    }

    @Test
    void beforeSendBeanIsWired() {
        runner.withUserConfiguration(BeforeSendConfig.class).run(ctx -> {
            AllStakConfig config = ctx.getBean(AllStakConfig.class);
            assertThat(config.getBeforeSend()).isNotNull();
            // The wired hook drops events (returns null).
            ErrorEvent dummy = new ErrorEvent("E", "m", null, "error", "test", null, null, null, null);
            assertThat(config.getBeforeSend().apply(dummy)).isNull();
        });
    }

    @Configuration
    static class BeforeSendConfig {
        @Bean
        AllStakBeforeSend dropAll() {
            return event -> null;
        }
    }
}
