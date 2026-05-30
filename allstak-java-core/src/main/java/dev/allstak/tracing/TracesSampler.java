package dev.allstak.tracing;

/**
 * Callback that returns a per-transaction sampling rate.
 *
 * <p>Use to weight high-value routes higher than chatty ones, for example:
 *
 * <pre>{@code
 * config = AllStakConfig.builder()
 *     .tracesSampler(ctx -> {
 *         String name = ctx.name();
 *         if (name == null) return 0.1;
 *         if (name.startsWith("GET /actuator")) return 0.0;
 *         if (name.startsWith("POST /api/orders")) return 1.0;
 *         return 0.2;
 *     })
 *     .tracesSampleRate(0.1)
 *     .build();
 * }</pre>
 *
 * <p>Returning {@code null} delegates back to {@code tracesSampleRate}. When
 * a remote parent's sampled bit is present the sampler still gets called:
 * the parent's bit only wins if the sampler returns {@code null}
 * AND no static rate is set.
 */
@FunctionalInterface
public interface TracesSampler {

    /**
     * Returns a probability in {@code [0.0, 1.0]} or {@code null} to defer
     * to the static {@code tracesSampleRate} / parent-sampled bit.
     */
    /*@Nullable*/ Double sample(SamplingContext context);
}
