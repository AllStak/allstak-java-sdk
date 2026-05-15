package dev.allstak.sample;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {SampleApplication.class, SampleAppFailOpenIntegrationTest.WireMockTransportConfig.class}
)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SampleAppFailOpenIntegrationTest {

    private static WireMockServer wireMock;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void wireMockProperties(DynamicPropertyRegistry registry) {
        registry.add("allstak.api-key", () -> "ask_live_fail_open_test_key");
        registry.add("allstak.environment", () -> "integration-test");
        registry.add("allstak.release", () -> "v1.0.0-test");
        registry.add("allstak.debug", () -> "true");
        registry.add("allstak.flush-interval-ms", () -> "100");
        registry.add("allstak.buffer-size", () -> "10");
        registry.add("allstak.service-name", () -> "sample-fail-open-test");
    }

    @TestConfiguration
    static class WireMockTransportConfig {
        @Bean
        public HttpTransport allStakHttpTransport() {
            return new HttpTransport(
                    "http://localhost:" + wireMock.port(),
                    "ask_live_fail_open_test_key"
            );
        }
    }

    @Test
    void servletRequestSucceedsQuicklyWhenIngestReturns503() throws Exception {
        wireMock.stubFor(post(urlPathMatching("/ingest/v1/.*"))
                .willReturn(aResponse().withStatus(503).withBody("{\"maintenance\":true}")));

        assertCustomerResponseIsFast();
    }

    @Test
    void servletRequestSucceedsQuicklyWhenIngestReturns429() throws Exception {
        wireMock.stubFor(post(urlPathMatching("/ingest/v1/.*"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "5")
                        .withBody("{\"rateLimited\":true}")));

        assertCustomerResponseIsFast();
    }

    @Test
    void servletRequestSucceedsQuicklyWhenIngestIsSlow() throws Exception {
        wireMock.stubFor(post(urlPathMatching("/ingest/v1/.*"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withFixedDelay(1_500)
                        .withBody("{\"maintenance\":true}")));

        assertCustomerResponseIsFast();
    }

    private void assertCustomerResponseIsFast() throws Exception {
        long started = System.nanoTime();
        mockMvc.perform(MockMvcRequestBuilders.get("/test/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("customer servlet response must not wait on AllStak ingest")
                .isLessThan(Duration.ofMillis(500));
    }
}
