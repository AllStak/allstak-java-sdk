package dev.allstak.spring;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.model.RequestContext;
import dev.allstak.transport.HttpTransport;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class EmbeddedKafkaFlowFixtureTest {
    private static final String TOPIC = "orders.embedded";
    private static final String DLT_TOPIC = "orders.embedded.DLT";
    private static WireMockServer ingest;
    private static EmbeddedKafkaKraftBroker kafka;

    private AllStakClient client;

    @BeforeAll
    static void startInfrastructure() {
        ingest = new WireMockServer(wireMockConfig().dynamicPort());
        ingest.start();
        ingest.stubFor(post(urlPathMatching("/ingest/v1/.*")).willReturn(aResponse().withStatus(202)));
        kafka = new EmbeddedKafkaKraftBroker(1, 1, TOPIC, DLT_TOPIC);
        kafka.afterPropertiesSet();
    }

    @AfterAll
    static void stopInfrastructure() {
        if (kafka != null) kafka.destroy();
        if (ingest != null) ingest.stop();
    }

    @BeforeEach
    void setup() {
        ingest.resetRequests();
        client = new AllStakClient(AllStakConfig.builder()
                .apiKey("ask_live_test_key")
                .host("http://localhost:" + ingest.port())
                .environment("embedded-kafka-test")
                .release("v1.0.0-kafka")
                .serviceName("embedded-kafka-test")
                .flushIntervalMs(100)
                .bufferSize(100)
                .build(), new HttpTransport("http://localhost:" + ingest.port(), "ask_live_test_key"));
        AllStakClient.setRequestContext(RequestContext.of("POST", "/orders", "app", (String) null, "trace-embedded-kafka", "req-embedded-kafka"));
    }

    @Test
    void embeddedKafkaPropagatesProducerHeadersToConsumerAndCapturesDltFailureMetadata() {
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(kafka);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringSerializer.class);
        KafkaTemplate<String, String> rawTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
        KafkaTemplate<String, String> template = (KafkaTemplate<String, String>) new AllStakKafkaTemplatePostProcessor(client)
                .postProcessAfterInitialization(rawTemplate, "embeddedKafkaTemplate");

        template.send(new ProducerRecord<>(TOPIC, "customer-42", "{}")).join();

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("allstak-fixture", "true", kafka);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringDeserializer.class);
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(consumerProps).createConsumer();
        kafka.consumeFromAnEmbeddedTopic(consumer, TOPIC);
        ConsumerRecord<String, String> received = KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(10));
        consumer.close();

        assertThat(received.headers().lastHeader(AllStakKafkaSupport.HEADER_TRACE_ID)).isNotNull();
        assertThat(received.headers().lastHeader(AllStakKafkaSupport.HEADER_REQUEST_ID)).isNotNull();

        KafkaFixtureListener listener = (KafkaFixtureListener) new AllStakKafkaListenerPostProcessor(client)
                .postProcessAfterInitialization(new KafkaFixtureListener(), "kafkaFixtureListener");
        listener.handle(received);

        ConsumerRecord<String, String> dltRecord = new ConsumerRecord<>(DLT_TOPIC, 0, 7L, "customer-42", "{}");
        dltRecord.headers().add(AllStakKafkaSupport.HEADER_TRACE_ID, "trace-dlt".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        dltRecord.headers().add(AllStakKafkaSupport.HEADER_REQUEST_ID, "req-dlt".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        dltRecord.headers().add("kafka_deliveryAttempt", "4".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> listener.fail(dltRecord)).isInstanceOf(IllegalStateException.class);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"messaging.destination.name\":\"" + TOPIC + "\""))
                    .withRequestBody(containing("\"messaging.system\":\"kafka\"")));
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                    .withRequestBody(containing("spring.kafka.listener"))
                    .withRequestBody(containing("req-dlt"))
                    .withRequestBody(containing("\"messaging.kafka.dead_letter\":true")));
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"messaging.destination.name\":\"" + DLT_TOPIC + "\""))
                    .withRequestBody(containing("\"messaging.kafka.delivery_attempt\":\"4\""))
                    .withRequestBody(containing("\"messaging.kafka.dead_letter\":\"true\"")));
        });
    }

    static class KafkaFixtureListener {
        @KafkaListener(topics = TOPIC, groupId = "allstak-fixture")
        void handle(ConsumerRecord<String, String> record) {}

        @KafkaListener(topics = DLT_TOPIC, groupId = "allstak-fixture")
        void fail(ConsumerRecord<String, String> record) {
            throw new IllegalStateException("embedded kafka dlt failure");
        }
    }
}
