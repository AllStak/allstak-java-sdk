package dev.allstak;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.allstak.model.UserContext;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.*;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.awaitility.Awaitility.await;

/**
 * Verifies the default privacy posture matches Sentry's
 * {@code sendDefaultPii=false} stance: email + ip stripped from outgoing
 * events unless the caller explicitly opts in.
 */
class PiiDefaultsTest {

    private static WireMockServer wireMock;

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
                        .withBody("{\"success\":true,\"data\":{\"id\":\"x\"}}")));
    }

    private AllStakClient client(boolean sendDefaultPii) {
        AllStakConfig cfg = AllStakConfig.builder()
                .apiKey("ask_live_test")
                .environment("test")
                .release("v0.0.1")
                .sendDefaultPii(sendDefaultPii)
                .enableAutoSessionTracking(false)
                .installUncaughtExceptionHandler(false)
                .build();
        HttpTransport tx = new HttpTransport("http://localhost:" + wireMock.port(), cfg.getApiKey());
        return new AllStakClient(cfg, tx);
    }

    @Test
    void default_stripsEmailAndIp_keepsId() {
        AllStakClient c = client(false);
        try {
            AllStak.init(c);
            AllStak.setUser(UserContext.of("user-42", "leak@example.com", "203.0.113.99"));

            c.captureException(new RuntimeException("boom"));

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                            .withRequestBody(containing("\"id\":\"user-42\""))
                            .withRequestBody(notContaining("leak@example.com"))
                            .withRequestBody(notContaining("203.0.113.99"))));
        } finally {
            AllStak.reset();
        }
    }

    @Test
    void sendDefaultPii_true_preservesEmailAndIp() {
        AllStakClient c = client(true);
        try {
            AllStak.init(c);
            AllStak.setUser(UserContext.of("user-77", "ok@example.com", "198.51.100.7"));

            c.captureException(new RuntimeException("kept"));

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                            .withRequestBody(containing("\"id\":\"user-77\""))
                            .withRequestBody(containing("ok@example.com"))
                            .withRequestBody(containing("198.51.100.7"))));
        } finally {
            AllStak.reset();
        }
    }

    @Test
    void default_userWithoutId_isDropped() {
        AllStakClient c = client(false);
        try {
            AllStak.init(c);
            AllStak.setUser(UserContext.of(null, "anon@example.com", "10.0.0.1"));

            c.captureException(new RuntimeException("anon"));

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    wireMock.verify(1, postRequestedFor(urlEqualTo("/ingest/v1/errors"))
                            .withRequestBody(notContaining("anon@example.com"))
                            .withRequestBody(notContaining("10.0.0.1"))));
        } finally {
            AllStak.reset();
        }
    }
}
