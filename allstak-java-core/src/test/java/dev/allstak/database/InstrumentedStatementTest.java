package dev.allstak.database;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.model.RequestContext;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.*;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class InstrumentedStatementTest {
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
                .environment("db-test")
                .release("v1.0.0-db")
                .serviceName("db-test")
                .flushIntervalMs(100)
                .bufferSize(100)
                .build(), new HttpTransport("http://localhost:" + ingest.port(), "ask_live_test_key"));
        AllStakClient.setRequestContext(RequestContext.of("GET", "/bookings", "api", (String) null, "trace-db", "req-db"));
    }

    @AfterEach
    void teardown() {
        AllStakClient.clearRequestContext();
        client.shutdown();
    }

    @Test
    void jdbcSuccessAndFailureCaptureDbQueryTelemetry() throws Exception {
        Statement successDelegate = statementProxy(false);
        InstrumentedStatement success = new InstrumentedStatement(successDelegate, client, "postgresql", "shreeksakn");
        success.execute("select * from bookings where id = 123");

        Statement failureDelegate = statementProxy(true);
        InstrumentedStatement failure = new InstrumentedStatement(failureDelegate, client, "postgresql", "shreeksakn");
        assertThatThrownBy(() -> failure.execute("select * from missing_table"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("relation missing_table does not exist");

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/db"))
                    .withRequestBody(containing("\"status\":\"success\""))
                    .withRequestBody(containing("\"queryType\":\"SELECT\""))
                    .withRequestBody(containing("\"traceId\":\"trace-db\"")));
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/db"))
                    .withRequestBody(containing("\"status\":\"error\""))
                    .withRequestBody(containing("relation missing_table does not exist")));
        });
    }

    private static Statement statementProxy(boolean fail) {
        return (Statement) Proxy.newProxyInstance(
                InstrumentedStatementTest.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("execute")) {
                        if (fail) throw new SQLException("relation missing_table does not exist");
                        if (method.getReturnType().equals(boolean.class)) return true;
                        if (method.getReturnType().equals(int.class)) return 1;
                        return null;
                    }
                    if (method.getReturnType().equals(boolean.class)) return false;
                    if (method.getReturnType().equals(int.class)) return 0;
                    return null;
                });
    }
}
