package dev.allstak.session;

import dev.allstak.AllStakConfig;
import dev.allstak.internal.SdkLogger;
import dev.allstak.transport.HttpTransport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server-mode "single session" tracker.
 *
 * <p>On {@link #start()} the SDK posts a {@code session/start} envelope with
 * the JVM's distinct id, the configured release, and an SDK identifier. On
 * {@link #end(SessionStatus)} it posts {@code session/end} with the final
 * status + total duration. Errored / crashed transitions are recorded
 * in-memory; only the terminal call performs network I/O so per-error
 * latency stays unaffected.
 *
 * <p>One instance per {@link dev.allstak.AllStakClient}. Re-entrancy safe:
 * once started a second {@link #start()} is a no-op; once ended the tracker
 * does not re-arm.
 */
public final class SessionTracker {

    private static final String PATH_START = "/ingest/v1/sessions/start";
    private static final String PATH_END   = "/ingest/v1/sessions/end";

    private final AllStakConfig config;
    private final HttpTransport transport;
    private final AtomicReference<Session> active = new AtomicReference<>();
    private volatile boolean ended = false;

    public SessionTracker(AllStakConfig config, HttpTransport transport) {
        this.config = config;
        this.transport = transport;
    }

    /**
     * Idempotent. Returns the session that became active (or the existing one).
     * The {@code /sessions/start} POST runs on a daemon thread so SDK init
     * never blocks the host application's startup on a network round-trip.
     */
    public Session start() {
        Session candidate = new Session();
        if (!active.compareAndSet(null, candidate)) {
            return active.get();
        }
        if (transport.isDisabled() || config.getRelease() == null || config.getRelease().isBlank()) {
            // No release ⇒ release-health cannot attribute the session. Keep
            // the in-memory tracker so errored/crashed transitions still set
            // a sensible final status, but skip the network call.
            return candidate;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", candidate.getId());
        payload.put("release",   config.getRelease());
        payload.put("environment", config.getEnvironment());
        payload.put("userId", null);
        payload.put("sdkName", config.getSdkName());
        payload.put("sdkVersion", config.getSdkVersion());
        payload.put("platform", config.getPlatform());

        Thread t = new Thread(() -> {
            try {
                transport.send(PATH_START, payload);
                SdkLogger.debug("Session started: {}", candidate.getId());
            } catch (Throwable err) {
                // Network failure must not crash app boot.
                SdkLogger.debug("Session start failed: {}", err.getMessage());
            }
        }, "allstak-session-start");
        t.setDaemon(true);
        t.start();
        return candidate;
    }

    /** Returns the active session or {@code null} if not started or already ended. */
    public Session current() {
        return ended ? null : active.get();
    }

    /** Record an error-level event against the active session. No I/O. */
    public void recordError() {
        Session s = current();
        if (s != null) s.recordError();
    }

    /** Record a crash. No I/O — the end-of-session POST carries the status. */
    public void recordCrash() {
        Session s = current();
        if (s != null) s.recordCrash();
    }

    /**
     * Terminate the session and POST {@code /sessions/end}. Idempotent. If
     * {@code finalStatus} is {@code null}, the session's own accumulated
     * status is used (OK / ERRORED / CRASHED / ABNORMAL).
     */
    public void end(SessionStatus finalStatus) {
        if (ended) return;
        Session s = active.getAndSet(null);
        if (s == null) return;
        ended = true;

        SessionStatus status = finalStatus != null ? finalStatus : s.getStatus();
        if (transport.isDisabled() || config.getRelease() == null || config.getRelease().isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", s.getId());
        payload.put("durationMs", (int) Math.min(Integer.MAX_VALUE, s.durationMs()));
        payload.put("status", status.wireValue());

        try {
            transport.send(PATH_END, payload);
            SdkLogger.debug("Session ended: {} status={} errors={}",
                    s.getId(), status.wireValue(), s.getErrorCount());
        } catch (Throwable t) {
            SdkLogger.debug("Session end failed: {}", t.getMessage());
        }
    }
}
