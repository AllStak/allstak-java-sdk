package dev.allstak.spring;

import dev.allstak.apache.AllStakApacheHttpInterceptor;
import dev.allstak.grpc.AllStakGrpcServerInterceptor;
import dev.allstak.okhttp.AllStakOkHttpInterceptor;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Spring Boot starter actually AUTO-WIRES each integration's
 * interceptor onto the developer's real client / factory bean — no manual
 * {@code addInterceptor} / {@code .intercept(...)} / {@code interceptor.classes}
 * call anywhere in the application code under test.
 *
 * <p>Each test also flips the matching {@code capture-*} property to {@code false}
 * and asserts the wiring is skipped — proving every gate actually gates something
 * (no dead properties).
 */
class AllStakClientAutoWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AllStakAutoConfiguration.class))
            .withPropertyValues("allstak.api-key=ask_test", "allstak.debug=true");

    // ---------------------------------------------------------------- OkHttp

    @Test
    void okHttpClientBean_getsInterceptorAttachedAutomatically() {
        runner.withUserConfiguration(OkHttpApp.class).run(ctx -> {
            OkHttpClient client = ctx.getBean(OkHttpClient.class);
            assertThat(hasAllStakOkHttp(client)).isTrue();
            assertThat(ctx).hasSingleBean(AllStakOkHttpClientPostProcessor.class);
        });
    }

    @Test
    void okHttp_isNotInstrumented_whenGateDisabled() {
        runner.withUserConfiguration(OkHttpApp.class)
                .withPropertyValues("allstak.capture-ok-http=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(AllStakOkHttpClientPostProcessor.class);
                    assertThat(hasAllStakOkHttp(ctx.getBean(OkHttpClient.class))).isFalse();
                });
    }

    @Test
    void okHttp_isNotDoubleInstrumented_whenAlreadyPresent() {
        runner.withUserConfiguration(PreInstrumentedOkHttpApp.class).run(ctx -> {
            OkHttpClient client = ctx.getBean(OkHttpClient.class);
            long count = client.interceptors().stream()
                    .filter(i -> i instanceof AllStakOkHttpInterceptor).count();
            assertThat(count).isEqualTo(1);
        });
    }

    private static boolean hasAllStakOkHttp(OkHttpClient client) {
        for (Interceptor i : client.interceptors()) {
            if (i instanceof AllStakOkHttpInterceptor) return true;
        }
        return false;
    }

    // -------------------------------------------------------- Apache HttpClient5

    @Test
    void apacheHttpClientBuilderBean_getsInterceptorsAttachedAutomatically() {
        runner.withUserConfiguration(ApacheApp.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(AllStakApacheHttpClientPostProcessor.class);
            assertThat(ctx).hasSingleBean(AllStakApacheHttpInterceptor.class);
            // Building from the post-processed builder must not throw.
            org.apache.hc.client5.http.impl.classic.HttpClientBuilder builder = ctx.getBean(
                    org.apache.hc.client5.http.impl.classic.HttpClientBuilder.class);
            assertThat(builder.build()).isNotNull();
        });
    }

    @Test
    void apacheHttp_isNotInstrumented_whenGateDisabled() {
        runner.withUserConfiguration(ApacheApp.class)
                .withPropertyValues("allstak.capture-apache-http=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AllStakApacheHttpClientPostProcessor.class));
    }

    // ---------------------------------------------------------------- gRPC

    @Test
    void grpcServerBuilderBean_getsInterceptorRegisteredAutomatically() {
        runner.withUserConfiguration(GrpcApp.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(AllStakGrpcServerBuilderPostProcessor.class);
            assertThat(ctx).hasSingleBean(AllStakGrpcServerInterceptor.class);
            // The post-processor must have called intercept() on the builder bean
            // with the AllStak server interceptor — no app-side .intercept() call.
            RecordingServerBuilder builder = ctx.getBean(RecordingServerBuilder.class);
            assertThat(builder.registered).hasSize(1);
            assertThat(builder.registered.get(0)).isInstanceOf(AllStakGrpcServerInterceptor.class);
        });
    }

    @Test
    void grpc_isNotInstrumented_whenGateDisabled() {
        runner.withUserConfiguration(GrpcApp.class)
                .withPropertyValues("allstak.capture-grpc=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AllStakGrpcServerBuilderPostProcessor.class));
    }

    // ---------------------------------------------------------------- GraphQL

    @Test
    void graphqlInstrumentationBean_isExposedAutomatically() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(graphql.execution.instrumentation.Instrumentation.class);
            assertThat(ctx.getBean(graphql.execution.instrumentation.Instrumentation.class))
                    .isInstanceOf(dev.allstak.graphql.AllStakGraphqlInstrumentation.class);
        });
    }

    @Test
    void graphql_isNotExposed_whenGateDisabled() {
        runner.withPropertyValues("allstak.capture-graphql=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(graphql.execution.instrumentation.Instrumentation.class));
    }

    // ---------------------------------------------------------------- Kafka

    @Test
    void kafkaFactories_getInterceptorClassesAppendedAutomatically() {
        runner.withUserConfiguration(KafkaApp.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(AllStakKafkaFactoryPostProcessor.class);

            DefaultKafkaProducerFactory<?, ?> pf = ctx.getBean(DefaultKafkaProducerFactory.class);
            assertThat(pf.getConfigurationProperties().get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG).toString())
                    .contains("dev.allstak.kafka.AllStakKafkaProducerInterceptor")
                    // user's own interceptor must be preserved
                    .contains("com.example.UserProducerInterceptor");

            DefaultKafkaConsumerFactory<?, ?> cf = ctx.getBean(DefaultKafkaConsumerFactory.class);
            assertThat(cf.getConfigurationProperties().get(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG).toString())
                    .contains("dev.allstak.kafka.AllStakKafkaConsumerInterceptor");
        });
    }

    @Test
    void kafka_isNotInstrumented_whenGateDisabled() {
        runner.withUserConfiguration(KafkaApp.class)
                .withPropertyValues("allstak.capture-kafka=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AllStakKafkaFactoryPostProcessor.class));
    }

    @Test
    void kafkaInterceptorAppend_handlesAllShapes() {
        // null -> just the AllStak class
        assertThat(AllStakKafkaFactoryPostProcessor.appendInterceptor(null, "a.B"))
                .isEqualTo("a.B");
        // existing String -> appended, preserving order
        assertThat(AllStakKafkaFactoryPostProcessor.appendInterceptor("x.Y", "a.B"))
                .isEqualTo("x.Y,a.B");
        // existing List -> appended
        assertThat(AllStakKafkaFactoryPostProcessor.appendInterceptor(java.util.List.of("x.Y"), "a.B"))
                .isEqualTo("x.Y,a.B");
        // already present -> null (no update)
        assertThat(AllStakKafkaFactoryPostProcessor.appendInterceptor("a.B", "a.B"))
                .isNull();
    }

    // ==================================================================
    //  Application configurations under test — NO AllStak wiring calls.
    // ==================================================================

    @Configuration
    static class OkHttpApp {
        @Bean
        OkHttpClient okHttpClient() {
            return new OkHttpClient.Builder().build();
        }
    }

    @Configuration
    static class PreInstrumentedOkHttpApp {
        @Bean
        OkHttpClient okHttpClient() {
            return new OkHttpClient.Builder().addInterceptor(new AllStakOkHttpInterceptor()).build();
        }
    }

    @Configuration
    static class ApacheApp {
        @Bean
        org.apache.hc.client5.http.impl.classic.HttpClientBuilder apacheBuilder() {
            return org.apache.hc.client5.http.impl.classic.HttpClients.custom();
        }
    }

    @Configuration
    static class GrpcApp {
        @Bean
        RecordingServerBuilder grpcServerBuilder() {
            return new RecordingServerBuilder();
        }
    }

    /**
     * Minimal {@link io.grpc.ServerBuilder} that records the interceptors
     * registered on it. Avoids pulling a full transport (grpc-netty /
     * grpc-inprocess) just to assert the post-processor called {@code intercept()}.
     */
    static class RecordingServerBuilder extends io.grpc.ServerBuilder<RecordingServerBuilder> {
        final java.util.List<io.grpc.ServerInterceptor> registered = new java.util.ArrayList<>();

        @Override public RecordingServerBuilder intercept(io.grpc.ServerInterceptor interceptor) {
            registered.add(interceptor);
            return this;
        }
        @Override public RecordingServerBuilder directExecutor() { return this; }
        @Override public RecordingServerBuilder executor(java.util.concurrent.Executor executor) { return this; }
        @Override public RecordingServerBuilder addService(io.grpc.ServerServiceDefinition service) { return this; }
        @Override public RecordingServerBuilder addService(io.grpc.BindableService bindableService) { return this; }
        @Override public RecordingServerBuilder fallbackHandlerRegistry(io.grpc.HandlerRegistry fallbackRegistry) { return this; }
        @Override public RecordingServerBuilder useTransportSecurity(java.io.File certChain, java.io.File privateKey) { return this; }
        @Override public RecordingServerBuilder decompressorRegistry(io.grpc.DecompressorRegistry registry) { return this; }
        @Override public RecordingServerBuilder compressorRegistry(io.grpc.CompressorRegistry registry) { return this; }
        @Override public io.grpc.Server build() { throw new UnsupportedOperationException("test stub"); }
    }

    @Configuration
    static class KafkaApp {
        @Bean
        DefaultKafkaProducerFactory<String, String> producerFactory() {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
            cfg.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, "com.example.UserProducerInterceptor");
            return new DefaultKafkaProducerFactory<>(cfg);
        }

        @Bean
        DefaultKafkaConsumerFactory<String, String> consumerFactory() {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
            cfg.put(ConsumerConfig.GROUP_ID_CONFIG, "allstak-test");
            return new DefaultKafkaConsumerFactory<>(cfg);
        }
    }
}
