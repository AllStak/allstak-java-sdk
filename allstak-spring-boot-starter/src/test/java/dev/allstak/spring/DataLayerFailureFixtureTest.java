package dev.allstak.spring;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.model.RequestContext;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class DataLayerFailureFixtureTest {
    private static WireMockServer ingest;
    private AllStakClient client;

    @BeforeAll
    static void startServer() {
        ingest = new WireMockServer(wireMockConfig().dynamicPort());
        ingest.start();
        ingest.stubFor(post(urlPathMatching("/ingest/v1/.*")).willReturn(aResponse().withStatus(202)));
    }

    @AfterAll
    static void stopServer() {
        if (ingest != null) ingest.stop();
    }

    @BeforeEach
    void setup() {
        ingest.resetRequests();
        client = new AllStakClient(AllStakConfig.builder()
                .apiKey("ask_live_test_key")
                .host("http://localhost:" + ingest.port())
                .environment("data-layer-test")
                .release("v1.0.0-data")
                .serviceName("data-layer-test")
                .flushIntervalMs(100)
                .bufferSize(100)
                .build(), new HttpTransport("http://localhost:" + ingest.port(), "ask_live_test_key"));
        AllStakClient.setRequestContext(RequestContext.of("POST", "/orders", "app", (String) null, "trace-db-fixture", "req-db-fixture"));
    }

    @AfterEach
    void teardown() {
        AllStakClient.clearRequestContext();
        client.shutdown();
    }

    @Test
    void jdbcAndTransactionRollbackFailuresEmitCorrelatedDbEvidence() {
        DataSource raw = new DriverManagerDataSource("jdbc:h2:mem:allstak_db_fixture;DB_CLOSE_DELAY=-1", "sa", "");
        DataSource wrapped = (DataSource) new AllStakDataSourcePostProcessor(client)
                .postProcessAfterInitialization(raw, "h2DataSource");
        JdbcTemplate jdbc = new JdbcTemplate(wrapped);
        jdbc.execute("create table booking (id int primary key, name varchar(64))");

        assertThatThrownBy(() -> jdbc.queryForList("select * from missing_table"))
                .isInstanceOf(Exception.class);

        PlatformTransactionManager txManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(wrapped);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.update("insert into booking (id, name) values (?, ?)", 1, "created-inside-rollback");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/db"))
                    .withRequestBody(containing("missing_table"))
                    .withRequestBody(containing("trace-db-fixture")));
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/db"))
                    .withRequestBody(containing("insert into booking")));
        });
    }
}
