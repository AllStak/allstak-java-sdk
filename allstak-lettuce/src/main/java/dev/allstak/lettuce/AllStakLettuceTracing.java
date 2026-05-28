package dev.allstak.lettuce;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import io.lettuce.core.tracing.TraceContext;
import io.lettuce.core.tracing.TraceContextProvider;
import io.lettuce.core.tracing.Tracer;
import io.lettuce.core.tracing.TracerProvider;
import io.lettuce.core.tracing.Tracing;
import reactor.core.publisher.Mono;

/**
 * Lettuce {@link Tracing} adapter that emits one AllStak span per Redis
 * command. Command names are kept (GET, SET, DEL); argument values are
 * NEVER captured — Redis keys frequently contain PII.
 *
 * <p>Wire it into the client resources:
 *
 * <pre>{@code
 * ClientResources resources = ClientResources.builder()
 *     .tracing(AllStakLettuceTracing.create())
 *     .build();
 * RedisClient.create(resources, redisUri);
 * }</pre>
 */
public final class AllStakLettuceTracing implements Tracing {

    private AllStakLettuceTracing() {}

    public static Tracing create() { return new AllStakLettuceTracing(); }

    @Override public TracerProvider getTracerProvider() { return new SimpleProvider(); }
    @Override public TraceContextProvider initialTraceContextProvider() { return SimpleContext::new; }
    @Override public boolean isEnabled() { return AllStak.getClient() != null; }
    @Override public boolean includeCommandArgsInSpanTags() { return false; }
    @Override public io.lettuce.core.tracing.Tracing.Endpoint createEndpoint(java.net.SocketAddress socketAddress) { return null; }

    private static final class SimpleProvider implements TracerProvider {
        @Override
        public Tracer getTracer() {
            return new Tracer() {
                @Override
                public Tracer.Span nextSpan() { return new SimpleSpan(); }
                @Override
                public Tracer.Span nextSpan(TraceContext traceContext) { return new SimpleSpan(); }
            };
        }
    }

    private static final class SimpleSpan extends Tracer.Span {
        private final long startNanos = System.nanoTime();
        private final String traceId = java.util.UUID.randomUUID().toString().replace("-", "");
        private final String spanId  = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        private String name = "redis";

        @Override public Tracer.Span name(String n) { this.name = "redis." + n; return this; }
        @Override public Tracer.Span tag(String k, String v) { return this; }
        @Override public Tracer.Span error(Throwable t) { return this; }
        @Override public Tracer.Span annotate(String value) { return this; }
        @Override public Tracer.Span remoteEndpoint(io.lettuce.core.tracing.Tracing.Endpoint endpoint) { return this; }
        @Override public Tracer.Span start(io.lettuce.core.protocol.RedisCommand<?, ?, ?> command) { return this; }
        @Override
        public void finish() {
            AllStakClient client = AllStak.getClient();
            if (client == null) return;
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            try {
                client.captureSpan(traceId, spanId, null, "db.redis", name, "ok",
                        durationMs, System.currentTimeMillis() - durationMs, System.currentTimeMillis(),
                        "lettuce", client.getConfig().getEnvironment(), null);
            } catch (Exception ignored) {}
        }
    }

    private static final class SimpleContext implements TraceContext {}
}
