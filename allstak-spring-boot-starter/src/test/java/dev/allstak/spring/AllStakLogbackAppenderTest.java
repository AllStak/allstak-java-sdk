package dev.allstak.spring;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
 * Behavioral tests for {@link AllStakLogbackAppender}. Focuses on the new
 * Sentry-parity behavior: {@code ERROR}-level events that carry a real
 * {@link Throwable} are promoted to {@code captureException} (Issues view)
 * in addition to being captured as a structured log.
 */
class AllStakLogbackAppenderTest {

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

    /** Detach any AllStak appender previously registered by other tests'
     *  Spring auto-configuration. Otherwise that lingering root-attached
     *  appender swallows any SLF4J emission inside captureException's HTTP
     *  path (Wiremock client / Apache HC), which double-counts events in
     *  this test's wiremock. */
    private List<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> stashedRootAppenders;
    private LoggerContext loggerCtx;

    @BeforeEach
    void setUp() {
        loggerCtx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = loggerCtx.getLogger(Logger.ROOT_LOGGER_NAME);
        stashedRootAppenders = new ArrayList<>();
        Iterator<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> it = root.iteratorForAppenders();
        while (it.hasNext()) {
            Appender<ch.qos.logback.classic.spi.ILoggingEvent> a = it.next();
            if (a instanceof AllStakLogbackAppender) {
                stashedRootAppenders.add(a);
            }
        }
        for (Appender<ch.qos.logback.classic.spi.ILoggingEvent> a : stashedRootAppenders) {
            root.detachAppender(a);
        }

        wireMock.resetAll();
        wireMock.stubFor(post(urlPathMatching("/ingest/v1/.*"))
                .willReturn(aResponse().withStatus(202)
                        .withBody("{\"success\":true,\"data\":{\"id\":\"test\"}}")));

        AllStakConfig config = AllStakConfig.builder()
                .apiKey("ask_live_logback_test")
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

        AllStak.shutdown();
        AllStak.init(client);
    }

    @AfterEach
    void tearDown() {
        AllStak.shutdown();
        client = null;
        // Restore any previously-registered root appenders so we don't break
        // tests that rely on Spring auto-configuration's wiring.
        if (loggerCtx != null && stashedRootAppenders != null) {
            Logger root = loggerCtx.getLogger(Logger.ROOT_LOGGER_NAME);
            for (Appender<ch.qos.logback.classic.spi.ILoggingEvent> a : stashedRootAppenders) {
                root.addAppender(a);
            }
        }
    }

    private LoggingEvent event(String loggerName, Level level, String message, Throwable thrown) {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = ctx.getLogger(loggerName);
        LoggingEvent e = new LoggingEvent(
                loggerName, logger, level, message, thrown, null);
        e.setTimeStamp(System.currentTimeMillis());
        return e;
    }

    @Test
    void errorWithThrowablePromotedToCaptureException() {
        AllStakLogbackAppender appender = new AllStakLogbackAppender();
        appender.start();

        // Reproduces the AuditLogWriter pattern: caught exception, logged with
        // log.error(msg, ex). Must surface in /ingest/v1/errors AND /logs.
        String marker = "Logback-promoted-marker-" + System.nanoTime();
        IllegalStateException boom = new IllegalStateException(marker);
        appender.append(event("com.techsea.urntapi.AuditLogWriter", Level.ERROR,
                "ClickHouse audit write failed: action=WHATSAPP_SENT", boom));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                    .withRequestBody(containing(marker))
                    .withRequestBody(containing("IllegalStateException")));
            wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                    .withRequestBody(containing(marker)));
        });
    }

    @Test
    void errorWithoutThrowableIsLogOnly() {
        AllStakLogbackAppender appender = new AllStakLogbackAppender();
        appender.start();

        String marker = "Logback-log-only-marker-" + System.nanoTime();
        appender.append(event("com.acme.Service", Level.ERROR, marker, null));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                        .withRequestBody(containing(marker))));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                .withRequestBody(containing(marker)));
    }

    @Test
    void warnWithThrowableIsLogOnly() {
        AllStakLogbackAppender appender = new AllStakLogbackAppender();
        appender.start();

        String marker = "Logback-warn-marker-" + System.nanoTime();
        appender.append(event("com.acme.Service", Level.WARN,
                marker, new RuntimeException("retryable")));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/logs"))
                        .withRequestBody(containing(marker))));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                .withRequestBody(containing(marker)));
    }

    @Test
    void sdkOwnLogsAreNotRecaptured() {
        AllStakLogbackAppender appender = new AllStakLogbackAppender();
        appender.start();

        String sdkMarker = "Logback-sdk-marker-" + System.nanoTime();
        String namedMarker = "Logback-named-marker-" + System.nanoTime();
        String realMarker = "Logback-real-marker-" + System.nanoTime();

        appender.append(event("dev.allstak.sdk", Level.ERROR, sdkMarker,
                new RuntimeException(sdkMarker)));
        appender.append(event("com.acme.AllStakClientThing", Level.ERROR, namedMarker,
                new RuntimeException(namedMarker)));
        appender.append(event("com.acme.Service", Level.ERROR, realMarker,
                new RuntimeException(realMarker)));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                wireMock.verify(postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                        .withRequestBody(containing(realMarker))));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                .withRequestBody(containing(sdkMarker)));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                .withRequestBody(containing(namedMarker)));
    }
}
