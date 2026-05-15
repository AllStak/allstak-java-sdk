package dev.allstak.internal;

import dev.allstak.buffer.RingBuffer;
import dev.allstak.transport.HttpTransport;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Background flush worker for a single feature's ring buffer.
 * Flushes on timer interval and when capacity threshold (80%) is reached.
 */
public final class FlushWorker<T> {

    private final String featureName;
    private final RingBuffer<T> buffer;
    private final Function<List<T>, Boolean> sendFn;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public FlushWorker(String featureName, RingBuffer<T> buffer, Function<List<T>, Boolean> sendFn) {
        this.featureName = featureName;
        this.buffer = buffer;
        this.sendFn = sendFn;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "allstak-flush-" + featureName);
            t.setDaemon(true);
            return t;
        });
    }

    public void start(long intervalMs) {
        if (running) return;
        running = true;
        scheduler.scheduleAtFixedRate(this::flush, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        SdkLogger.debug("Flush worker started for '{}' with {}ms interval", featureName, intervalMs);
    }

    public void flush() {
        try {
            if (buffer.isEmpty()) return;
            List<T> items = buffer.drain();
            if (items.isEmpty()) return;
            SdkLogger.debug("Flushing {} {} item(s)", items.size(), featureName);
            sendFn.apply(items);
        } catch (Exception e) {
            SdkLogger.debug("Flush error for '{}': {}", featureName, e.getMessage());
        }
    }

    /**
     * Check capacity threshold and flush if needed. Called after each add to the buffer.
     */
    public void checkCapacityFlush() {
        if (buffer.isAtCapacityThreshold()) {
            scheduler.execute(this::flush);
        }
    }

    public void shutdown() {
        running = false;
        try {
            // Runtime shutdown must be fail-open. Explicit client.flush() is
            // available for callers that want a best-effort drain, but normal
            // framework/application shutdown must never wait on AllStak
            // availability.
            scheduler.shutdown();
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            SdkLogger.debug("Flush worker '{}' shut down", featureName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }
}
