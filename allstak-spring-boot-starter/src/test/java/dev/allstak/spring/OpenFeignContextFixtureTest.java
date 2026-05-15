package dev.allstak.spring;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(
        classes = OpenFeignContextFixtureTest.FeignFixtureApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "allstak.api-key=ask_live_test_key",
                "allstak.environment=openfeign-test",
                "allstak.release=v1.0.0-openfeign",
                "allstak.service-name=openfeign-test",
                "allstak.flush-interval-ms=100"
        })
class OpenFeignContextFixtureTest {
    private static final WireMockServer ingest = new WireMockServer(wireMockConfig().dynamicPort());
    private static final WireMockServer target = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        ingest.start();
        target.start();
    }

    @Autowired
    TestFeignClient client;

    @BeforeAll
    static void startServers() {
        ingest.resetAll();
        ingest.stubFor(post(urlPathMatching("/ingest/v1/.*")).willReturn(aResponse().withStatus(202)));
        target.resetAll();
        target.stubFor(get(urlEqualTo("/ok")).willReturn(aResponse().withStatus(200).withBody("ok")));
        target.stubFor(get(urlEqualTo("/fail")).willReturn(aResponse().withStatus(503).withBody("downstream unavailable")));
    }

    @AfterAll
    static void stopServers() {
        target.stop();
        ingest.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("allstak.host", () -> "http://localhost:" + ingest.port());
        registry.add("test.feign.url", () -> "http://localhost:" + target.port());
    }

    @Test
    void springCloudOpenFeignPropagatesHeadersAndCapturesSuccessAndFailure() {
        client.ok();
        assertThatThrownBy(() -> client.fail()).isInstanceOf(Exception.class);

        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
            target.verify(getRequestedFor(urlEqualTo("/ok"))
                    .withHeader(AllStakFeignRequestInterceptor.HEADER_TRACEPARENT, matching(".+"))
                    .withHeader(AllStakFeignRequestInterceptor.HEADER_TRACE_ID, matching(".+"))
                    .withHeader(AllStakFeignRequestInterceptor.HEADER_REQUEST_ID, matching(".+")));
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/http-requests"))
                    .withRequestBody(containing("\"direction\":\"outbound\""))
                    .withRequestBody(containing("\"path\":\"/ok\"")));
            ingest.verify(postRequestedFor(urlEqualTo("/ingest/v1/http-requests"))
                    .withRequestBody(containing("\"direction\":\"outbound\""))
                    .withRequestBody(containing("\"path\":\"/fail\""))
                    .withRequestBody(containing("\"statusCode\":503")));
        });
    }

    @Configuration
    @EnableAutoConfiguration
    @EnableFeignClients(clients = TestFeignClient.class)
    static class FeignFixtureApp {}

    @FeignClient(name = "testFeignClient", url = "${test.feign.url}")
    interface TestFeignClient {
        @GetMapping("/ok")
        String ok();

        @GetMapping("/fail")
        String fail();
    }
}
