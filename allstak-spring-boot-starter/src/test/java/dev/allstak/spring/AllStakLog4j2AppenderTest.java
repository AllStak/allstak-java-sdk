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
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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

    /** Other tests' Spring auto-configuration leaves an AllStakLogbackAppender
     *  on the root logger. It would catch any SLF4J emission inside this
     *  test's captureException path and double-count events. Detach for the
     *  duration of this test. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List stashedRootAppenders;
    private Object loggerCtx;

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        Object f = LoggerFactory.getILoggerFactory();
        if (f instanceof ch.qos.logback.classic.LoggerContext ctx) {
            loggerCtx = ctx;
            ch.qos.logback.classic.Logger root = ctx.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
            stashedRootAppenders = new ArrayList();
            Iterator it = root.iteratorForAppenders();
            while (it.hasNext()) {
                Object a = it.next();
                if (a instanceof AllStakLogbackAppender) stashedRootAppenders.add(a);
            }
            for (Object a : stashedRootAppenders) {
                root.detachAppender((ch.qos.logback.core.Appender) a);
            }
        }

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
    @SuppressWarnings({"rawtypes", "unchecked"})
    void tearDown() {
        AllStak.shutdown();
        client = null;
        if (loggerCtx instanceof ch.qos.logback.classic.LoggerContext ctx && stashedRootAppenders != null) {
            ch.qos.logback.classic.Logger root = ctx.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
            for (Object a : stashedRootAppenders) {
                root.addAppender((ch.qos.logback.core.Appender) a);
            }
        }
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
    void errorWithThrowableAlsoPromotedToCaptureException() {
        AllStakLog4j2Appender appender = AllStakLog4j2Appender.create("allstak");
        appender.start();

        // Caught-and-logged exception: host code did `log.error(msg, ex)`.
        // The appender must fire BOTH a log entry and an error event so the
        // throwable surfaces in the AllStak Issues view, not just in Logs.
        String marker = "Log4j2-promoted-marker-" + System.nanoTime();
        IllegalStateException boom = new IllegalStateException(marker);
        appender.append(event("com.acme.AuditWriter", Level.ERROR,
                "audit store write failed: action=X", boom));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                    .withRequestBody(containing(marker))
                    .withRequestBody(containing("IllegalStateException")));
            wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                    .withRequestBody(containing(marker)));
        });
    }

    @Test
    void errorWithoutThrowableDoesNotHitErrorsEndpoint() {
        AllStakLog4j2Appender appender = AllStakLog4j2Appender.create("allstak");
        appender.start();

        String marker = "Log4j2-log-only-marker-" + System.nanoTime();
        appender.append(event("com.acme.Service", Level.ERROR, marker, null));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                        .withRequestBody(containing(marker))));
        // No error event must carry this marker — string-only log.error has no
        // throwable to promote.
        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                .withRequestBody(containing(marker)));
    }

    @Test
    void warnWithThrowableStaysAsLogOnly() {
        AllStakLog4j2Appender appender = AllStakLog4j2Appender.create("allstak");
        appender.start();

        // WARN-with-throwable is intentionally NOT promoted — only ERROR+ is.
        String marker = "Log4j2-warn-marker-" + System.nanoTime();
        appender.append(event("com.acme.Service", Level.WARN,
                marker, new RuntimeException("retryable")));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                        .withRequestBody(containing(marker))));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                .withRequestBody(containing(marker)));
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
