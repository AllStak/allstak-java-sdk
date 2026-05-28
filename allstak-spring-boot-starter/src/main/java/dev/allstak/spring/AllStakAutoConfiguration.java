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
                .tracesSampleRate(props.getTracesSampleRate())
                .sendDefaultPii(props.isSendDefaultPii())
                .enableAutoSessionTracking(props.isEnableAutoSessionTracking())
                .tracePropagationTargets(props.getTracePropagationTargets());
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

    /**
     * Log4j2 counterpart of the Logback registrar. Activated only when Log4j2
     * core is the active backend and Logback is NOT on the classpath, so apps
     * that use Logback never double-capture. Isolated in a nested configuration
     * so the outer class never reflectively touches Log4j2 types when Log4j2 is
     * absent (Spring eagerly reads all method signatures on auto-config classes
     * regardless of per-method {@code @ConditionalOnClass}).
     */
    @org.springframework.context.annotation.Configuration
    @ConditionalOnClass(name = "org.apache.logging.log4j.core.LoggerContext")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass("ch.qos.logback.classic.Logger")
    public static class Log4j2AutoConfiguration {

        @Bean
        @ConditionalOnProperty(prefix = "allstak", name = "capture-logs", havingValue = "true", matchIfMissing = true)
        public Object allStakLog4j2AppenderRegistrar(AllStakClient client) {
            try {
                org.apache.logging.log4j.core.LoggerContext ctx =
                    (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
                org.apache.logging.log4j.core.config.Configuration logConfig = ctx.getConfiguration();
                AllStakLog4j2Appender appender = AllStakLog4j2Appender.create("allstak");
                appender.start();
                logConfig.addAppender(appender);
                logConfig.getRootLogger().addAppender(appender, null, null);
                ctx.updateLoggers();
                SdkLogger.debug("AllStak Log4j2 appender registered");
            } catch (Exception e) {
                SdkLogger.debug("Failed to register Log4j2 appender: {}", e.getMessage());
            }
            return "allstak-log4j2-registered";
        }
    }

    // =====================================================================
    // Phase B/C/E auto-configurations — each gated by its library so the
    // outer class stays loadable on minimal classpaths. Spring eagerly
    // resolves method return types on the OUTER class only, so every
    // optional integration lives in its own nested @Configuration.
    // =====================================================================

    /** Phase B.1 — OkHttp interceptor on every {@code OkHttpClient.Builder}. */
    @org.springframework.context.annotation.Configuration
    @ConditionalOnClass(name = "okhttp3.OkHttpClient")
    @ConditionalOnProperty(prefix = "allstak", name = "capture-ok-http", havingValue = "true", matchIfMissing = true)
    public static class OkHttpAutoConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public dev.allstak.okhttp.AllStakOkHttpInterceptor allStakOkHttpInterceptor() {
            return new dev.allstak.okhttp.AllStakOkHttpInterceptor();
        }
    }

    /** Phase B.2 — Apache HttpClient 5 request+response interceptor. */
    @org.springframework.context.annotation.Configuration
    @ConditionalOnClass(name = "org.apache.hc.core5.http.HttpRequest")
    @ConditionalOnProperty(prefix = "allstak", name = "capture-apache-http", havingValue = "true", matchIfMissing = true)
    public static class ApacheHttp5AutoConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public dev.allstak.apache.AllStakApacheHttpInterceptor allStakApacheHttpInterceptor() {
            return new dev.allstak.apache.AllStakApacheHttpInterceptor();
        }
    }

    /** Phase B.3 — Feign RequestInterceptor. */
    @org.springframework.context.annotation.Configuration
    @ConditionalOnClass(name = "feign.RequestInterceptor")
    @ConditionalOnProperty(prefix = "allstak", name = "capture-feign", havingValue = "true", matchIfMissing = true)
    public static class FeignAutoConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public dev.allstak.feign.AllStakFeignInterceptor allStakFeignInterceptor() {
            return new dev.allstak.feign.AllStakFeignInterceptor();
        }
    }

    /** Phase B.4 — install the Reactor onEachOperator hook at startup. */
    @org.springframework.context.annotation.Configuration
    @ConditionalOnClass(name = "reactor.core.publisher.Hooks")
    @ConditionalOnProperty(prefix = "allstak", name = "capture-reactor", havingValue = "true", matchIfMissing = true)
    public static class ReactorAutoConfiguration {
        @Bean
        public Object allStakReactorHookInstaller() {
            dev.allstak.reactor.AllStakReactorHooks.register();
            SdkLogger.debug("AllStak Reactor scope-propagation hook installed");
            return "allstak-reactor-hook";
        }
    }

    /** Phase B.5 — register the AllStak {@code JobListener} on every Quartz Scheduler bean. */
    @org.springframework.context.annotation.Configuration
    @ConditionalOnClass(name = "org.quartz.Scheduler")
    @ConditionalOnProperty(prefix = "allstak", name = "capture-quartz", havingValue = "true", matchIfMissing = true)
    public static class QuartzAutoConfiguration {
        @Bean
        public dev.allstak.quartz.AllStakQuartzJobListener allStakQuartzJobListener() {
            return new dev.allstak.quartz.AllStakQuartzJobListener();
        }
        @Bean
        public org.springframework.beans.factory.config.BeanPostProcessor allStakQuartzListenerInstaller(
                dev.allstak.quartz.AllStakQuartzJobListener listener) {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof org.quartz.Scheduler scheduler) {
                        try { scheduler.getListenerManager().addJobListener(listener); }
                        catch (Exception e) { SdkLogger.debug("Quartz listener install failed: {}", e.getMessage()); }
                    }
                    return bean;
                }
            };
        }
    }

    /** Phase B.7 — push the Spring Security principal into the active scope on every request. */
    @org.springframework.context.annotation.Configuration
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    @ConditionalOnProperty(prefix = "allstak", name = "capture-security-user", havingValue = "true", matchIfMissing = true)
    public static class SpringSecurityAutoConfiguration {
        /** Marker bean. The {@link AllStakServletFilter} already runs per request;
         *  enrich it here so the user lands in the scope before any error is captured. */
        @Bean
        public org.springframework.web.servlet.HandlerInterceptor allStakSecurityUserHandlerInterceptor() {
            return new org.springframework.web.servlet.HandlerInterceptor() {
                @Override
                public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                                          jakarta.servlet.http.HttpServletResponse response,
                                          Object handler) {
                    dev.allstak.spring_security.AllStakSecurityUserEnricher.apply();
                    return true;
                }
            };
        }
    }

    /** Phase C.5 — wrap every {@code @Bean Cache} with the AllStak decorator. */
    @org.springframework.context.annotation.Configuration
    @ConditionalOnClass(name = "org.springframework.cache.Cache")
    @ConditionalOnProperty(prefix = "allstak", name = "capture-spring-cache", havingValue = "true", matchIfMissing = true)
    public static class SpringCacheAutoConfiguration {
        @Bean
        public org.springframework.beans.factory.config.BeanPostProcessor allStakCacheDecorator() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof org.springframework.cache.Cache cache
                            && !(cache instanceof dev.allstak.spring_cache.AllStakCacheSpanDecorator)) {
                        return dev.allstak.spring_cache.AllStakCacheSpanDecorator.wrap(cache);
                    }
                    return bean;
                }
            };
        }
    }

    /** Phase E.2 — start a JFR profiler at app boot, stop on shutdown. */
    @org.springframework.context.annotation.Configuration
    @ConditionalOnClass(name = "dev.allstak.profiling_jfr.AllStakJfrProfiler")
    @ConditionalOnProperty(prefix = "allstak", name = "enable-profiling", havingValue = "true")
    public static class JfrProfilerAutoConfiguration {
        @Bean(destroyMethod = "stop")
        public dev.allstak.profiling_jfr.AllStakJfrProfiler allStakJfrProfiler() {
            return dev.allstak.profiling_jfr.AllStakJfrProfiler.start();
        }
    }

    @PreDestroy
    public void shutdown() {
        SdkLogger.debug("Spring context shutting down — flushing AllStak SDK");
        AllStak.shutdown();
    }
}
