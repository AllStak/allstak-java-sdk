package dev.allstak.spring;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.model.RequestContext;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.RabbitMQContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class RabbitDlqBrokerFixtureTest {
    private static final String EXCHANGE = "allstak.orders";
    private static final String QUEUE = "allstak.orders.primary";
    private static final String ROUTING_KEY = "orders.created";
    private static final String DLX = "allstak.orders.dlx";
    private static final String DLQ = "allstak.orders.dlq";
    private static final String DLQ_ROUTING_KEY = "orders.created.dead";

    private static WireMockServer ingest;
    private static RabbitMQContainer rabbit;
    private AllStakClient client;

    @BeforeAll
    static void startInfrastructure() {
        ingest = new WireMockServer(wireMockConfig().dynamicPort());
        ingest.start();
        ingest.stubFor(post(urlPathMatching("/ingest/v1/.*")).willReturn(aResponse().withStatus(202)));
    }

    @AfterAll
    static void stopInfrastructure() {
        if (rabbit != null) rabbit.stop();
        if (ingest != null) ingest.stop();
    }

    @BeforeEach
    void setup() {
        ingest.resetRequests();
        client = new AllStakClient(AllStakConfig.builder()
                .apiKey("ask_live_test_key")
                .host("http://localhost:" + ingest.port())
                .environment("rabbit-dlq-test")
                .release("v1.0.0-rabbit")
                .serviceName("rabbit-dlq-test")
                .flushIntervalMs(100)
                .bufferSize(100)
                .build(), new HttpTransport("http://localhost:" + ingest.port(), "ask_live_test_key"));
        AllStakClient.setRequestContext(RequestContext.of("POST", "/orders", "app", (String) null, "trace-rabbit-broker", "req-rabbit-broker"));
    }

    @Test
    void rabbitBrokerRoutesRejectedMessageToDlqAndSdkCapturesXDeathMetadata() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker/Testcontainers is unavailable for RabbitMQ DLQ fixture");
        rabbit = new RabbitMQContainer("rabbitmq:3.13-management");
        rabbit.start();
        CachingConnectionFactory cf = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
        cf.setUsername(rabbit.getAdminUsername());
        cf.setPassword(rabbit.getAdminPassword());
        RabbitAdmin admin = new RabbitAdmin(cf);
        admin.declareExchange(new DirectExchange(EXCHANGE, true, false));
        admin.declareExchange(new DirectExchange(DLX, true, false));
        admin.declareQueue(QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build());
        admin.declareQueue(QueueBuilder.durable(DLQ).build());
        admin.declareBinding(BindingBuilder.bind(new Queue(QUEUE)).to(new DirectExchange(EXCHANGE)).with(ROUTING_KEY));
        admin.declareBinding(BindingBuilder.bind(new Queue(DLQ)).to(new DirectExchange(DLX)).with(DLQ_ROUTING_KEY));

        RabbitTemplate rawTemplate = new RabbitTemplate(cf);
        RabbitTemplate template = (RabbitTemplate) new AllStakRabbitTemplatePostProcessor(client)
                .postProcessAfterInitialization(rawTemplate, "rabbitTemplate");
        Message outbound = MessageBuilder.withBody("{}".getBytes(StandardCharsets.UTF_8))
                .setMessageId("msg-rabbit-broker")
                .build();
        template.send(EXCHANGE, ROUTING_KEY, outbound);

        try (Connection connection = cf.createConnection().getDelegate(); Channel channel = connection.createChannel()) {
            com.rabbitmq.client.GetResponse response = channel.basicGet(QUEUE, false);
            assertThat(response).isNotNull();
            assertThat(response.getProps().getHeaders()).containsKeys(
                    AllStakRabbitSupport.HEADER_TRACE_ID,
                    AllStakRabbitSupport.HEADER_REQUEST_ID,
                    AllStakRabbitSupport.HEADER_TRACEPARENT);
            channel.basicReject(response.getEnvelope().getDeliveryTag(), false);
        }

        Message dlqMessage = rawTemplate.receive(DLQ, 10_000);
        assertThat(dlqMessage).isNotNull();
        RabbitFixtureListener listener = (RabbitFixtureListener) new AllStakRabbitListenerPostProcessor(client)
                .postProcessAfterInitialization(new RabbitFixtureListener(), "rabbitFixtureListener");
        assertThatThrownBy(() -> listener.fail(dlqMessage)).isInstanceOf(IllegalStateException.class);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"messaging.system\":\"rabbitmq\""))
                    .withRequestBody(containing("\"span.kind\":\"messaging.producer\"")));
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                    .withRequestBody(containing("spring.rabbit.listener"))
                    .withRequestBody(containing("\"messaging.rabbitmq.x_death_count\""))
                    .withRequestBody(containing(DLX)));
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/spans"))
                    .withRequestBody(containing("\"span.kind\":\"messaging.consumer\""))
                    .withRequestBody(containing("\"messaging.rabbitmq.x_death_count\":\"1\""))
                    .withRequestBody(containing("\"messaging.rabbitmq.exchange\":\"" + DLX + "\""))
                    .withRequestBody(containing("\"messaging.rabbitmq.routing_key\":\"" + DLQ_ROUTING_KEY + "\""))
                    .withRequestBody(containing("\"messaging.destination\":\"" + DLQ + "\"")));
        });
        cf.destroy();
    }

    static class RabbitFixtureListener {
        @RabbitListener(queues = DLQ)
        void fail(Message message) {
            throw new IllegalStateException("rabbit dlq failure");
        }
    }
}
