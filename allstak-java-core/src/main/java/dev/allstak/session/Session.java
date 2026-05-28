package dev.allstak.session;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A single release-health session. One-per-JVM in the default single-mode
 * deployment; future request-mode sessions would create one instance per
 * inbound request. Mutable through atomic counters / references so multiple
 * threads can mark the session errored/crashed without locking.
 */
public final class Session {

    private final String id;
    private final Instant startedAt;
    private final AtomicReference<SessionStatus> status = new AtomicReference<>(SessionStatus.OK);
    private final AtomicInteger errorCount = new AtomicInteger();

    public Session() {
        this(UUID.randomUUID().toString(), Instant.now());
    }

    public Session(String id, Instant startedAt) {
        this.id = id;
        this.startedAt = startedAt;
    }

    public String getId() { return id; }
    public Instant getStartedAt() { return startedAt; }
    public SessionStatus getStatus() { return status.get(); }
    public int getErrorCount() { return errorCount.get(); }

    /**
     * Increment the error counter and bump status to {@link SessionStatus#ERRORED}
     * unless the session has already escalated to a terminal status.
     */
    public void recordError() {
        errorCount.incrementAndGet();
        status.compareAndSet(SessionStatus.OK, SessionStatus.ERRORED);
    }

    /** Mark a terminal crashed status (overrides ERRORED). Used by uncaught handler. */
    public void recordCrash() {
        status.set(SessionStatus.CRASHED);
        errorCount.incrementAndGet();
    }

    /** Promote to {@link SessionStatus#ABNORMAL} only if still OK or ERRORED. */
    public void recordAbnormalExit() {
        status.compareAndSet(SessionStatus.OK, SessionStatus.ABNORMAL);
        status.compareAndSet(SessionStatus.ERRORED, SessionStatus.ABNORMAL);
    }

    /** Duration from start to now, capped at 0. */
    public long durationMs() {
        long d = Instant.now().toEpochMilli() - startedAt.toEpochMilli();
        return Math.max(0L, d);
    }
}
