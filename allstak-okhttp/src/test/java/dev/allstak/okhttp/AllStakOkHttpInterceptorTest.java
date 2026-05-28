package dev.allstak.okhttp;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.transport.HttpTransport;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AllStakOkHttpInterceptorTest {

    private MockWebServer mock;

    @BeforeEach
    void setUp() throws Exception {
        mock = new MockWebServer();
        mock.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        AllStak.reset();
        mock.shutdown();
    }

    private void initSdk(List<String> targets) {
        AllStakConfig cfg = AllStakConfig.builder()
                .apiKey("ask_live_test")
                .environment("test")
                .release("v0.0.1")
                .enableAutoSessionTracking(false)
                .installUncaughtExceptionHandler(false)
                .tracePropagationTargets(targets)
                .build();
        // Transport pointed at an unreachable URL so capture is a silent no-op.
        AllStak.init(new AllStakClient(cfg, new HttpTransport("http://127.0.0.1:1", cfg.getApiKey())));
    }

    @Test
    void injectsTraceHeaders_whenTargetMatches() throws Exception {
        initSdk(List.of(mock.getHostName()));
        mock.enqueue(new MockResponse().setResponseCode(200));

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new AllStakOkHttpInterceptor())
                .build();
        try (Response r = client.newCall(new Request.Builder().url(mock.url("/x")).build()).execute()) {
            assertThat(r.code()).isEqualTo(200);
        }

        RecordedRequest rr = mock.takeRequest(2, TimeUnit.SECONDS);
        assertThat(rr).isNotNull();
        assertThat(rr.getHeader(AllStakOkHttpInterceptor.HEADER_TRACE_ID)).isNotBlank();
        assertThat(rr.getHeader(AllStakOkHttpInterceptor.HEADER_SPAN_ID)).isNotBlank();
        assertThat(rr.getHeader(AllStakOkHttpInterceptor.HEADER_TRACEPARENT)).startsWith("00-");
    }

    @Test
    void omitsTraceHeaders_whenTargetNotOnAllowlist() throws Exception {
        initSdk(List.of("not-this-host.example"));
        mock.enqueue(new MockResponse().setResponseCode(200));

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new AllStakOkHttpInterceptor())
                .build();
        try (Response r = client.newCall(new Request.Builder().url(mock.url("/y")).build()).execute()) {
            assertThat(r.code()).isEqualTo(200);
        }

        RecordedRequest rr = mock.takeRequest(2, TimeUnit.SECONDS);
        assertThat(rr).isNotNull();
        assertThat(rr.getHeader(AllStakOkHttpInterceptor.HEADER_TRACE_ID)).isNull();
        assertThat(rr.getHeader(AllStakOkHttpInterceptor.HEADER_TRACEPARENT)).isNull();
    }

    @Test
    void worksWithoutSdkInitialised() throws Exception {
        // No AllStak.init() — interceptor must be a no-op on context.
        mock.enqueue(new MockResponse().setResponseCode(204));
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new AllStakOkHttpInterceptor())
                .build();
        try (Response r = client.newCall(new Request.Builder().url(mock.url("/")).build()).execute()) {
            assertThat(r.code()).isEqualTo(204);
        }
        RecordedRequest rr = mock.takeRequest(2, TimeUnit.SECONDS);
        assertThat(rr).isNotNull();
        assertThat(rr.getHeader(AllStakOkHttpInterceptor.HEADER_TRACE_ID)).isNull();
    }
}
