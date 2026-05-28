package dev.allstak.r2dbc;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import io.r2dbc.proxy.core.QueryExecutionInfo;
import io.r2dbc.proxy.listener.ProxyExecutionListener;

import java.util.UUID;

/**
 * R2DBC ProxyExecutionListener that emits a span per query execution.
 * Wire on the connection factory:
 *
 * <pre>{@code
 * ConnectionFactory raw = …;
 * ConnectionFactory wrapped = ProxyConnectionFactory.builder(raw)
 *     .listener(new AllStakR2dbcProxyListener())
 *     .build();
 * }</pre>
 */
public final class AllStakR2dbcProxyListener implements ProxyExecutionListener {

    @Override
    public void afterQuery(QueryExecutionInfo execInfo) {
        AllStakClient client = AllStak.getClient();
        if (client == null) return;
        long durationMs = execInfo.getExecuteDuration() == null ? 0 : execInfo.getExecuteDuration().toMillis();
        boolean ok = execInfo.getThrowable() == null;
        try {
            client.captureSpan(
                    UUID.randomUUID().toString().replace("-", ""),
                    UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                    null, "db.r2dbc",
                    "r2dbc " + execInfo.getType(),
                    ok ? "ok" : "error",
                    durationMs,
                    System.currentTimeMillis() - durationMs,
                    System.currentTimeMillis(),
                    "r2dbc", client.getConfig().getEnvironment(),
                    null);
        } catch (Exception ignored) {}
    }
}
