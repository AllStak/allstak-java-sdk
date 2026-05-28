package dev.allstak.jedis;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;

import java.util.UUID;

/**
 * Manual Jedis instrumentation helper. Jedis (unlike Lettuce) has no
 * official tracing SPI, so callers wrap critical commands explicitly:
 *
 * <pre>{@code
 * AllStakJedisInstrumentation.timed("GET", () -> jedis.get("user-42"));
 * }</pre>
 *
 * <p>Spring users can also drop a {@code @Bean BeanPostProcessor} that
 * wraps the {@code JedisPool} but that's beyond the safe-by-default scope
 * of this module — pool wrappers can subtly change resource semantics.
 */
public final class AllStakJedisInstrumentation {

    private AllStakJedisInstrumentation() {}

    /** Runs {@code work}, emits an AllStak span tagged with the command name, returns the result. */
    public static <T> T timed(String commandName, java.util.concurrent.Callable<T> work) throws Exception {
        AllStakClient client = AllStak.getClient();
        long start = System.nanoTime();
        Throwable failure = null;
        T result = null;
        try {
            result = work.call();
            return result;
        } catch (Exception | Error e) {
            failure = e;
            throw e;
        } finally {
            if (client != null) {
                long durMs = (System.nanoTime() - start) / 1_000_000L;
                String traceId = UUID.randomUUID().toString().replace("-", "");
                String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                try {
                    client.captureSpan(traceId, spanId, null, "db.redis", "redis." + commandName,
                            failure == null ? "ok" : "error",
                            durMs, System.currentTimeMillis() - durMs, System.currentTimeMillis(),
                            "jedis", client.getConfig().getEnvironment(), null);
                } catch (Exception ignored) {}
                AllStak.addBreadcrumb("query", "redis " + commandName,
                        failure == null ? "info" : "error",
                        java.util.Map.of("duration_ms", durMs));
            }
        }
    }
}
