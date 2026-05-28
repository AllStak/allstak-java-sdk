package dev.allstak.opentelemetry;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * OpenTelemetry {@link SpanProcessor} that forwards finished spans into
 * the AllStak transport. Lets customers keep their OpenTelemetry agent
 * (or manual OTel SDK) and dual-write to AllStak without standing up
 * the AllStak-native instrumentation modules.
 *
 * <p>Register on the OTel SDK builder:
 *
 * <pre>{@code
 * SdkTracerProvider provider = SdkTracerProvider.builder()
 *     .addSpanProcessor(new AllStakOtelSpanProcessor())
 *     .build();
 * }</pre>
 */
public final class AllStakOtelSpanProcessor implements SpanProcessor {

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        // Intentionally cheap — the heavy work happens on end so onStart
        // doesn't add latency to the host application.
    }

    @Override
    public boolean isStartRequired() { return false; }

    @Override
    public void onEnd(ReadableSpan span) {
        AllStakClient client = AllStak.getClient();
        if (client == null) return;
        SpanData data = span.toSpanData();
        long durationMs = (data.getEndEpochNanos() - data.getStartEpochNanos()) / 1_000_000L;
        String status = data.getStatus().getStatusCode() == StatusCode.ERROR ? "error" : "ok";
        try {
            client.captureSpan(
                    data.getTraceId(),
                    data.getSpanId(),
                    data.getParentSpanId(),
                    data.getName(),
                    data.getName(),
                    status,
                    durationMs,
                    data.getStartEpochNanos() / 1_000_000L,
                    data.getEndEpochNanos() / 1_000_000L,
                    "opentelemetry",
                    client.getConfig().getEnvironment(),
                    null);
        } catch (Exception ignored) {}
    }

    @Override
    public boolean isEndRequired() { return true; }
}
