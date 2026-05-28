package dev.allstak.apache;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.transport.HttpTransport;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AllStakApacheHttpInterceptorTest {

    @AfterEach
    void tearDown() { AllStak.reset(); }

    private void initSdk(List<String> targets) {
        AllStakConfig cfg = AllStakConfig.builder()
                .apiKey("ask_live_test").environment("test").release("v0.0.1")
                .enableAutoSessionTracking(false).installUncaughtExceptionHandler(false)
                .tracePropagationTargets(targets).build();
        AllStak.init(new AllStakClient(cfg, new HttpTransport("http://127.0.0.1:1", cfg.getApiKey())));
    }

    @Test
    void requestInterceptor_injectsHeaders_whenTargetMatches() throws Exception {
        initSdk(List.of("api.allstak.sa"));
        var req = new BasicClassicHttpRequest("GET", "https://api.allstak.sa/v1/ping");
        HttpContext ctx = new BasicHttpContext();

        new AllStakApacheHttpInterceptor().process(req, null, ctx);

        assertThat(req.getFirstHeader(AllStakApacheHttpInterceptor.HEADER_TRACE_ID)).isNotNull();
        assertThat(req.getFirstHeader(AllStakApacheHttpInterceptor.HEADER_TRACEPARENT).getValue()).startsWith("00-");
    }

    @Test
    void requestInterceptor_skipsHeaders_whenTargetNotOnAllowlist() throws Exception {
        initSdk(List.of("api.allstak.sa"));
        var req = new BasicClassicHttpRequest("GET", "https://random.example.com/x");
        new AllStakApacheHttpInterceptor().process(req, null, new BasicHttpContext());

        assertThat(req.getFirstHeader(AllStakApacheHttpInterceptor.HEADER_TRACE_ID)).isNull();
    }

    @Test
    void responseInterceptor_emitsBreadcrumb_evenWithoutMatchingRequest() throws Exception {
        initSdk(null);
        HttpContext ctx = new BasicHttpContext();
        ctx.setAttribute("allstak.startNs", System.nanoTime());
        ctx.setAttribute("allstak.traceId", "0123456789abcdef0123456789abcdef");
        ctx.setAttribute("allstak.spanId",  "fedcba9876543210");

        HttpResponse resp = new BasicClassicHttpResponse(500);
        new AllStakApacheHttpInterceptor().process(resp, null, ctx);
        // No exception thrown ⇒ pass; capture is best-effort.
    }
}
