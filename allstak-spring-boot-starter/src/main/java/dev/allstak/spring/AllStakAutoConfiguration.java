package dev.allstak.spring;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.internal.SdkLogger;
import dev.allstak.transport.HttpTransport;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Spring Boot auto-configuration for AllStak SDK.
 * Activated when {@code allstak.enabled=true} (default) and required properties are present.
 */
@AutoConfiguration
@EnableConfigurationProperties(AllStakProperties.class)
@ConditionalOnProperty(prefix = "allstak", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AllStakAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AllStakConfig allStakConfig(AllStakProperties props,
                                       org.springframework.beans.factory.ObjectProvider<AllStakBeforeSend> beforeSend) {
        AllStakConfig.Builder builder = AllStakConfig.builder()
                .apiKey(props.getApiKey())
                .environment(props.getEnvironment())
                .release(props.getRelease())
                .debug(props.isDebug())
                .flushIntervalMs(props.getFlushIntervalMs())
                .bufferSize(props.getBufferSize())
                .serviceName(props.getServiceName())
                .installUncaughtExceptionHandler(props.isInstallUncaughtExceptionHandler())
                .sampleRate(props.getSampleRate())
                .tracesSampleRate(props.getTracesSampleRate());
        // Optional user-supplied beforeSend hook, registered as a bean.
        AllStakBeforeSend hook = beforeSend.getIfAvailable();
        if (hook != null) {
            builder.beforeSend(hook::beforeSend);
        }
        return builder.build();
    }

    /**
     * HTTP transport. Exposed as a bean so integration tests can override it
     * (e.g. point at a WireMock server) without touching the static ingest host.
     */
    @Bean
    @ConditionalOnMissingBean
    public HttpTransport allStakHttpTransport(AllStakConfig config) {
        return new HttpTransport(config.getHost(), config.getApiKey());
    }

    @Bean
    @ConditionalOnMissingBean
    public AllStakClient allStakClient(AllStakConfig config, HttpTransport transport) {
        AllStakClient client = new AllStakClient(config, transport);
        // Register with the static facade as well
        AllStak.init(client);
        SdkLogger.debug("AllStak client bean created and registered with static facade");
        return client;
    }

    @Bean
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    @ConditionalOnProperty(prefix = "allstak", name = "capture-http-requests", havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<AllStakServletFilter> allStakServletFilter(AllStakClient client) {
        FilterRegistrationBean<AllStakServletFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AllStakServletFilter(client));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("allStakServletFilter");
        return registration;
    }

    @Bean
    @ConditionalOnProperty(prefix = "allstak", name = "capture-exceptions", havingValue = "true", matchIfMissing = true)
    public AllStakExceptionHandler allStakExceptionHandler(AllStakClient client) {
        return new AllStakExceptionHandler(client);
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.web.client.RestTemplate")
    public AllStakRestTemplateInterceptor allStakRestTemplateInterceptor(AllStakClient client) {
        return new AllStakRestTemplateInterceptor(client);
    }

    /**
     * Customizer picked up by Spring Boot's {@code RestTemplateBuilder} bean,
     * so every {@code RestTemplateBuilder#build()} call automatically attaches
     * the AllStak outbound interceptor.
     */
    @Bean
    @ConditionalOnClass(name = {
        "org.springframework.web.client.RestTemplate",
        "org.springframework.boot.web.client.RestTemplateCustomizer"
    })
    public AllStakRestTemplateCustomizer allStakRestTemplateCustomizer(AllStakRestTemplateInterceptor interceptor) {
        return new AllStakRestTemplateCustomizer(interceptor);
    }

    /**
     * Post-processor that attaches the AllStak interceptor to every
     * {@code RestTemplate} bean in the context — covers the path where users
     * construct a {@code new RestTemplate()} directly in a {@code @Bean}
     * method instead of going through {@code RestTemplateBuilder}.
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.web.client.RestTemplate")
    public AllStakRestTemplatePostProcessor allStakRestTemplatePostProcessor(AllStakRestTemplateInterceptor interceptor) {
        return new AllStakRestTemplatePostProcessor(interceptor);
    }

    /**
     * Reactive outbound HTTP capture. Isolated in a nested configuration so
     * the outer class never reflectively touches {@code WebClient} types when
     * WebFlux is not on the classpath (Spring's reflection on auto-config
     * classes eagerly reads all method signatures regardless of
     * {@code @ConditionalOnClass} on individual methods).
     */
    @org.springframework.context.annotation.Configuration
    @ConditionalOnClass(name = "org.springframework.web.reactive.function.client.WebClient")
    public static class WebClientAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public AllStakWebClientFilter allStakWebClientFilter(AllStakClient client) {
            return new AllStakWebClientFilter(client);
        }

        @Bean
        @ConditionalOnMissingBean
        public AllStakWebClientCustomizer allStakWebClientCustomizer(AllStakWebClientFilter filter) {
            return new AllStakWebClientCustomizer(filter);
        }
    }

    /**
     * Auto-wraps every {@code @Scheduled} bean method with an AllStak cron
     * heartbeat so users don't have to write {@code startJob/finishJob}
     * boilerplate. Activated only when Spring's {@code @Scheduled} annotation
     * is on the classpath.
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.scheduling.annotation.Scheduled")
    @ConditionalOnProperty(prefix = "allstak", name = "capture-scheduled", havingValue = "true", matchIfMissing = true)
    public AllStakScheduledPostProcessor allStakScheduledPostProcessor(AllStakClient client) {
        return new AllStakScheduledPostProcessor(client);
    }

    @Bean
    @ConditionalOnClass(name = "javax.sql.DataSource")
    @ConditionalOnProperty(prefix = "allstak", name = "capture-db-queries", havingValue = "true", matchIfMissing = true)
    public AllStakDataSourcePostProcessor allStakDataSourcePostProcessor(AllStakClient client) {
        return new AllStakDataSourcePostProcessor(client);
    }

    @Bean
    @ConditionalOnClass(name = "ch.qos.logback.classic.Logger")
    @ConditionalOnProperty(prefix = "allstak", name = "capture-logs", havingValue = "true", matchIfMissing = true)
    public Object allStakLogbackAppenderRegistrar(AllStakClient client) {
        try {
            ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            AllStakLogbackAppender appender = new AllStakLogbackAppender();
            appender.setContext(rootLogger.getLoggerContext());
            appender.setName("allstak");
            appender.start();
            rootLogger.addAppender(appender);
            SdkLogger.debug("AllStak Logback appender registered");
        } catch (Exception e) {
            SdkLogger.debug("Failed to register Logback appender: {}", e.getMessage());
        }
        return "allstak-logback-registered";
    }

    @PreDestroy
    public void shutdown() {
        SdkLogger.debug("Spring context shutting down — flushing AllStak SDK");
        AllStak.shutdown();
    }
}
