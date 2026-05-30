package dev.allstak.spring;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class AllStakTraceHeadersTest {

    @Test
    void fromContinuesValidTraceparentAndIgnoresInvalidCustomTraceHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        request.addHeader("X-AllStak-Trace-Id", "not-a-valid-trace-id");

        AllStakTraceHeaders headers = AllStakTraceHeaders.from(request);

        assertThat(headers.traceId).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(headers.parentSpanId).isEqualTo("b7ad6b7169203331");
    }

    @Test
    void fromRejectsInvalidAndAllZeroTraceContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-00000000000000000000000000000000-0000000000000000-01");
        request.addHeader("X-AllStak-Trace-Id", "not-a-valid-trace-id");
        request.addHeader("X-AllStak-Span-Id", "also-invalid");

        AllStakTraceHeaders headers = AllStakTraceHeaders.from(request);

        assertThat(headers.traceId).matches("^[0-9a-f]{32}$");
        assertThat(headers.traceId).isNotEqualTo("00000000000000000000000000000000");
        assertThat(headers.parentSpanId).isEmpty();
    }

    @Test
    void applyNormalizesUuidFormIdsForW3cTraceparent() {
        HttpHeaders target = new HttpHeaders();

        AllStakTraceHeaders.apply(
                target,
                "7f3ac1d9-2b8e-4a6f-8c1a-000000000001",
                "req-1",
                "abcdef01-2345-6789-abcd-ef0123456789",
                "01");

        assertThat(target.getFirst("traceparent"))
                .isEqualTo("00-7f3ac1d92b8e4a6f8c1a000000000001-abcdef0123456789-01");
        assertThat(target.getFirst("X-AllStak-Trace-Id"))
                .isEqualTo("7f3ac1d92b8e4a6f8c1a000000000001");
        assertThat(target.getFirst("X-AllStak-Span-Id"))
                .isEqualTo("abcdef0123456789");
        assertThat(target.getFirst("baggage"))
                .contains("allstak-trace_id=7f3ac1d92b8e4a6f8c1a000000000001")
                .contains("allstak-span_id=abcdef0123456789");
    }
}
