package dev.allstak.spring;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.transport.HttpTransport;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.awaitility.Awaitility.await;

/**
 * Behavioral tests for {@link AllStakLog4j2Appender}. These mirror the
 * semantics exercised by the Logback appender: events route through
 * {@link AllStakClient#captureLog}, throwable stacks are folded into the
 * message, the configured threshold is respected, and the SDK's own internal
 * log lines are never re-captured (recursion guard).
 *
 * <p>The appender is driven with synthetic Log4j2 {@link LogEvent}s and the
 * resulting ingest traffic is asserted against a WireMock ingest endpoint —
 * the same approach used by the core client integration test.
 */
class AllStakLog4j2AppenderTest {

    private static WireMockServer wireMock;
    private AllStakClient client;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        wireMock.stubFor(post(urlPathMatching("/ingest/v1/.*"))
                .willReturn(aResponse().withStatus(202)
                        .withBody("{\"success\":true,\"data\":{\"id\":\"test\"}}")));

        AllStakConfig config = AllStakConfig.builder()
                .apiKey("ask_live_log4j2_test")
                .environment("test")
                .release("v0.0.1-test")
                .debug(false)
                .flushIntervalMs(300)
                .bufferSize(100)
                .serviceName("test-service")
                .installUncaughtExceptionHandler(false)
                .build();

        HttpTransport transport = new HttpTransport(
                "http://localhost:" + wireMock.port(), config.getApiKey());
        client = new AllStakClient(config, transport);

        AllStak.shutdown();     // clear any prior facade state
        AllStak.init(client);   // appender resolves the client via AllStak.getClient()
    }

    @AfterEach
    void tearDown() {
        AllStak.shutdown(); // shuts down and clears the registered client
        client = null;
    }

    private LogEvent event(String loggerName, Level level, String message, Throwable thrown) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName(loggerName)
                .setLevel(level)
                .setMessage(new SimpleMessage(message))
                .setThrown(thrown)
                .build();
    }

    @Test
    void errorWithThrowableProducesOneLogWithStack() {
        AllStakLog4j2Appender appender = AllStakLog4j2Appender.create("allstak");
        appender.start();

        RuntimeException boom = new RuntimeException("kaboom");
        appender.append(event("com.acme.Service", Level.ERROR, "request failed", boom));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                        .withRequestBody(containing("\"level\":\"error\""))
                        .withRequestBody(containing("request failed"))
                        .withRequestBody(containing("java.lang.RuntimeException: kaboom"))
                        .withRequestBody(containing("AllStakLog4j2AppenderTest"))));
    }

    @Test
    void lowerLevelEventBecomesBreadcrumbAttachedToNextError() {
        AllStakLog4j2Appender appender = AllStakLog4j2Appender.create("allstak");
        appender.start();

        // An INFO log is captured as a log; a WARN log is also promoted to a
        // breadcrumb by captureLog (auto-breadcrumbs). The subsequent captured
        // exception should carry that breadcrumb — mirroring Logback semantics.
        appender.append(event("com.acme.Service", Level.WARN, "low disk space", null));
        client.captureException(new IllegalStateException("downstream failed"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                        .withRequestBody(containing("\"breadcrumbs\":["))
                        .withRequestBody(containing("low disk space"))));
    }

    @Test
    void thresholdIsRespected() {
        // Threshold WARN: INFO must be dropped, WARN must be captured.
        AllStakLog4j2Appender appender =
                new AllStakLog4j2Appender("allstak", null, Level.WARN);
        appender.start();

        appender.append(event("com.acme.Service", Level.INFO, "below threshold info", null));
        appender.append(event("com.acme.Service", Level.WARN, "at threshold warn", null));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                        .withRequestBody(containing("at threshold warn"))));
        // The INFO line must never have been sent.
        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                .withRequestBody(containing("below threshold info")));
    }

    @Test
    void doesNotRecursivelyCaptureSdkOwnLogs() {
        AllStakLog4j2Appender appender = AllStakLog4j2Appender.create("allstak");
        appender.start();

        // Logger names belonging to the SDK are skipped outright.
        appender.append(event("dev.allstak.sdk", Level.ERROR, "internal sdk error", null));
        appender.append(event("com.acme.AllStakClientThing", Level.ERROR, "named after sdk", null));
        // SDK debug messages (by message prefix) are also skipped.
        appender.append(event("com.acme.Service", Level.INFO, "[AllStak SDK DEBUG] internal noise", null));
        // A genuine user log to prove the appender otherwise works.
        appender.append(event("com.acme.Service", Level.INFO, "genuine user log", null));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                        .withRequestBody(containing("genuine user log"))));

        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                .withRequestBody(containing("internal sdk error")));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                .withRequestBody(containing("named after sdk")));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                .withRequestBody(containing("internal noise")));
    }
}
