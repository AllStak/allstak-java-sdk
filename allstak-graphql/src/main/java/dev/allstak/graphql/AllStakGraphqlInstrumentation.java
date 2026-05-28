package dev.allstak.graphql;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import graphql.ExecutionResult;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;

import java.util.UUID;

/**
 * graphql-java {@link SimplePerformantInstrumentation} extension. Emits
 * one AllStak span per execution. Field-level errors fall through the
 * standard {@code GraphQLError} channel; per-field spans are deliberately
 * not emitted (they'd explode cardinality on large queries).
 *
 * <p>Wire on the builder:
 *
 * <pre>{@code
 * GraphQL graphQL = GraphQL.newGraphQL(schema)
 *     .instrumentation(new AllStakGraphqlInstrumentation())
 *     .build();
 * }</pre>
 */
public final class AllStakGraphqlInstrumentation extends SimplePerformantInstrumentation {

    @Override
    public InstrumentationContext<ExecutionResult> beginExecution(
            InstrumentationExecutionParameters parameters,
            InstrumentationState state) {
        long startNs = System.nanoTime();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String spanId  = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return new InstrumentationContext<>() {
            @Override public void onDispatched() {}
            @Override
            public void onCompleted(ExecutionResult result, Throwable t) {
                AllStakClient client = AllStak.getClient();
                if (client == null) return;
                long durMs = (System.nanoTime() - startNs) / 1_000_000L;
                String op = parameters.getOperation() == null ? "graphql.execute"
                        : "graphql." + parameters.getOperation();
                try {
                    client.captureSpan(traceId, spanId, null, "graphql", op,
                            t == null ? "ok" : "error",
                            durMs, System.currentTimeMillis() - durMs, System.currentTimeMillis(),
                            "graphql-java", client.getConfig().getEnvironment(), null);
                } catch (Exception ignored) {}
                if (t != null) AllStak.captureException(t);
            }
        };
    }
}
