package dev.allstak;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.allstak.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void errorEvent_serialization() throws Exception {
        ErrorEvent event = new ErrorEvent(
                "NullPointerException",
                "Cannot invoke method on null",
                List.of("at com.example.Foo.bar(Foo.java:42)"),
                "error",
                "production",
                "v1.0.0",
                null,
                UserContext.of("user-1", "user@test.com", "127.0.0.1"),
                Map.of("key", "value")
        );

        String json = mapper.writeValueAsString(event);
        assertThat(json).contains("\"exceptionClass\":\"NullPointerException\"");
        assertThat(json).contains("\"message\":\"Cannot invoke method on null\"");
        assertThat(json).contains("\"level\":\"error\"");
        assertThat(json).contains("\"environment\":\"production\"");
        assertThat(json).contains("\"stackTrace\":[");
        assertThat(json).contains("\"user\":{");
        assertThat(json).contains("\"id\":\"user-1\"");
    }

    @Test
    void errorEvent_nullFieldsExcluded() throws Exception {
        ErrorEvent event = new ErrorEvent(
                "RuntimeException", "test", null, "error",
                null, null, null, null, null);

        String json = mapper.writeValueAsString(event);
        assertThat(json).doesNotContain("stackTrace");
        assertThat(json).doesNotContain("sessionId");
        assertThat(json).doesNotContain("user");
        assertThat(json).doesNotContain("metadata");
        assertThat(json).doesNotContain("environment");
    }

    @Test
    void logEvent_serialization() throws Exception {
        LogEvent event = new LogEvent("warn", "Payment retry", "payment-service",
                "trace-123", Map.of("orderId", "ORD-1"));

        String json = mapper.writeValueAsString(event);
        assertThat(json).contains("\"level\":\"warn\"");
        assertThat(json).contains("\"service\":\"payment-service\"");
        assertThat(json).contains("\"traceId\":\"trace-123\"");
    }

    @Test
    void httpRequestBatch_serialization() throws Exception {
        HttpRequestItem item = HttpRequestItem.builder()
                .traceId("trace-001")
                .direction("inbound")
                .method("GET")
                .host("api.example.com")
                .path("/v1/orders")
                .statusCode(200)
                .durationMs(142)
                .requestSize(0)
                .responseSize(2048)
                .timestamp("2026-03-31T12:00:00Z")
                .requestBody("{\"email\":\"[REDACTED]\",\"ok\":true}")
                .requestBodyCaptureStatus("redacted")
                .requestBodyCaptureReason("request body captured with sensitive fields redacted.")
                .build();

        HttpRequestBatch batch = new HttpRequestBatch(List.of(item));
        String json = mapper.writeValueAsString(batch);

        assertThat(json).contains("\"requests\":[");
        assertThat(json).contains("\"direction\":\"inbound\"");
        assertThat(json).contains("\"durationMs\":142");
        assertThat(json).contains("\"requestBodyCaptureStatus\":\"redacted\"");
        assertThat(json).contains("\"requestBodyCaptureReason\":\"request body captured with sensitive fields redacted.\"");
    }

    @Test
    void heartbeatEvent_serialization() throws Exception {
        HeartbeatEvent event = new HeartbeatEvent("daily-report", "success", 4320, "Processed 100 records");

        String json = mapper.writeValueAsString(event);
        assertThat(json).contains("\"slug\":\"daily-report\"");
        assertThat(json).contains("\"status\":\"success\"");
        assertThat(json).contains("\"durationMs\":4320");
        assertThat(json).contains("\"message\":\"Processed 100 records\"");
    }

    @Test
    void heartbeatEvent_nullMessageExcluded() throws Exception {
        HeartbeatEvent event = new HeartbeatEvent("test", "failed", 100, null);
        String json = mapper.writeValueAsString(event);
        assertThat(json).doesNotContain("\"message\"");
    }

    @Test
    void userContext_nullFieldsExcluded() throws Exception {
        UserContext user = UserContext.ofId("user-1");
        String json = mapper.writeValueAsString(user);
        assertThat(json).contains("\"id\":\"user-1\"");
        assertThat(json).doesNotContain("email");
        assertThat(json).doesNotContain("ip");
    }
}
