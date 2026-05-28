package dev.allstak.mongodb;

import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import dev.allstak.AllStak;
import dev.allstak.AllStakClient;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MongoDB driver {@link CommandListener} that emits one AllStak span per
 * command. Command name is captured ({@code find}, {@code insert},
 * {@code aggregate}, …) plus the database/collection. The query body is
 * NEVER captured.
 *
 * <p>Register on the {@code MongoClientSettings} builder:
 *
 * <pre>{@code
 * MongoClientSettings.builder()
 *     .addCommandListener(new AllStakMongoCommandListener())
 *     .build();
 * }</pre>
 */
public final class AllStakMongoCommandListener implements CommandListener {

    private final ConcurrentHashMap<Integer, InFlight> inFlight = new ConcurrentHashMap<>();

    @Override
    public void commandStarted(CommandStartedEvent event) {
        inFlight.put(event.getRequestId(),
                new InFlight(System.nanoTime(), event.getCommandName(), event.getDatabaseName()));
    }

    @Override
    public void commandSucceeded(CommandSucceededEvent event) {
        InFlight start = inFlight.remove(event.getRequestId());
        if (start != null) emit(start, "ok");
    }

    @Override
    public void commandFailed(CommandFailedEvent event) {
        InFlight start = inFlight.remove(event.getRequestId());
        if (start != null) emit(start, "error");
    }

    private void emit(InFlight start, String status) {
        AllStakClient client = AllStak.getClient();
        if (client == null) return;
        long durationMs = (System.nanoTime() - start.startNs) / 1_000_000L;
        String op = "mongodb." + start.commandName;
        try {
            client.captureSpan(
                    UUID.randomUUID().toString().replace("-", ""),
                    UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                    null, "db", op, status,
                    durationMs,
                    System.currentTimeMillis() - durationMs,
                    System.currentTimeMillis(),
                    "mongodb", client.getConfig().getEnvironment(),
                    java.util.Map.of("db.name", start.database));
        } catch (Exception ignored) {}
    }

    private record InFlight(long startNs, String commandName, String database) {}
}
