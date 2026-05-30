package dev.allstak.session;

import dev.allstak.AllStakConfig;
import dev.allstak.internal.SdkLogger;
import dev.allstak.transport.HttpTransport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final String STATE_VERSION = "1";
    private static final long STATE_MAX_AGE_MS = Duration.ofDays(7).toMillis();
    private static final long RECOVERY_LOCK_MS = Duration.ofSeconds(30).toMillis();
    private static final int RECOVERY_MAX_ATTEMPTS = 3;

    private final AllStakConfig config;
    private final HttpTransport transport;
    private final Path statePath;
    private final AtomicReference<Session> active = new AtomicReference<>();
    private final AtomicLong recoveryCount = new AtomicLong();
    private volatile boolean ended = false;

    public SessionTracker(AllStakConfig config, HttpTransport transport) {
        this(config, transport, null);
    }

    public SessionTracker(AllStakConfig config, HttpTransport transport, Path statePath) {
        this.config = config;
        this.transport = transport;
        this.statePath = statePath;
    }

    /**
     * Idempotent. Returns the session that became active (or the existing one).
     * The {@code /sessions/start} POST runs on a daemon thread so SDK init
     * never blocks the host application's startup on a network round-trip.
     *
     * @see #start(String) overload that attaches the active user id.
     */
    public Session start() {
        return start(null);
    }

    /**
     * Idempotent. Same as {@link #start()} but attaches {@code userId} to the
     * {@code /sessions/start} envelope when a user is set at init time.
     *
     * <p>Release-health sessions are <b>never sampled</b>: the start POST is
     * always attempted (subject only to the transport being enabled). When no
     * release is resolved the SDK falls back to the SDK version so the session
     * is still attributable rather than dropped.
     */
    public Session start(String userId) {
        Session candidate = new Session();
        if (!active.compareAndSet(null, candidate)) {
            return active.get();
        }
        recoverPreviousSession();
        writeOpenState(candidate, userId);
        if (transport.isDisabled()) {
            // Transport explicitly disabled (e.g. missing/blank key). Keep the
            // in-memory tracker so errored/crashed transitions still set a
            // sensible final status, but skip the network call.
            return candidate;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", candidate.getId());
        payload.put("release",   resolveRelease());
        payload.put("environment", config.getEnvironment());
        payload.put("userId", userId);
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

    /**
     * The id of the active session, or {@code null} when no session is open.
     * Attached to every captured error/event payload so the backend's error
     * consumer can mark the session errored/crashed server-side.
     */
    public String currentSessionId() {
        Session s = current();
        return s != null ? s.getId() : null;
    }

    /** Number of previous open sessions recovered by this tracker. */
    public long recoveryCount() {
        return recoveryCount.get();
    }

    /** Record an error-level event against the active session. No I/O. */
    public void recordError() {
        Session s = current();
        if (s != null) {
            s.recordError();
            writeOpenState(s, null);
        }
    }

    /** Record a crash. No I/O — the end-of-session POST carries the status. */
    public void recordCrash() {
        Session s = current();
        if (s != null) {
            s.recordCrash();
            writeOpenState(s, null);
        }
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
        writeClosedState(s, status);
        if (transport.isDisabled()) {
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

    /**
     * The release identifier carried on the session envelope. Falls back to the
     * SDK version when no release was resolved so a release-health session is
     * never dropped for lack of a release (the {@code /sessions/start} contract
     * requires a non-null release).
     */
    private String resolveRelease() {
        String release = config.getRelease();
        if (release != null && !release.isBlank()) return release;
        return config.getSdkVersion();
    }

    private void recoverPreviousSession() {
        Properties previous = readState();
        if (previous == null) return;
        long now = System.currentTimeMillis();
        if ("true".equals(previous.getProperty("closed"))) {
            removeState();
            return;
        }
        long startedAt = parseLong(previous.getProperty("startedAt"), -1);
        if (startedAt <= 0 || now - startedAt > STATE_MAX_AGE_MS) {
            removeState();
            return;
        }
        int attempts = (int) parseLong(previous.getProperty("recoveryAttempts"), 0);
        if (attempts >= RECOVERY_MAX_ATTEMPTS) {
            removeState();
            return;
        }
        long lockUntil = parseLong(previous.getProperty("recoveryLockUntil"), 0);
        if (lockUntil > now) return;

        String owner = java.util.UUID.randomUUID().toString();
        previous.setProperty("recoveryAttempts", Integer.toString(attempts + 1));
        previous.setProperty("recoveryLockOwner", owner);
        previous.setProperty("recoveryLockUntil", Long.toString(now + RECOVERY_LOCK_MS));
        previous.setProperty("updatedAt", Long.toString(now));
        writeState(previous);
        Properties claimed = readState();
        if (claimed == null || !owner.equals(claimed.getProperty("recoveryLockOwner"))) return;

        String status = SessionStatus.CRASHED.wireValue().equals(previous.getProperty("status"))
                ? SessionStatus.CRASHED.wireValue()
                : SessionStatus.ABNORMAL.wireValue();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", previous.getProperty("sessionId"));
        payload.put("durationMs", Math.max(0, parseLong(previous.getProperty("updatedAt"), now) - startedAt));
        payload.put("status", status);
        try {
            if (!transport.isDisabled()) transport.send(PATH_END, payload);
            previous.setProperty("status", status);
            previous.setProperty("closed", "true");
            previous.setProperty("endedAt", Long.toString(now));
            previous.setProperty("recoveredAt", Long.toString(now));
            previous.setProperty("recoveryLockUntil", "0");
            writeState(previous);
            recoveryCount.incrementAndGet();
        } catch (Throwable t) {
            previous.setProperty("recoveryLockUntil", "0");
            writeState(previous);
            SdkLogger.debug("Session recovery failed: {}", t.getMessage());
        }
    }

    private void writeOpenState(Session s, String userId) {
        if (statePath == null) return;
        Properties p = baseState(s, s.getStatus());
        p.setProperty("closed", "false");
        if (userId != null) p.setProperty("userId", userId);
        writeState(p);
    }

    private void writeClosedState(Session s, SessionStatus status) {
        if (statePath == null) return;
        Properties p = baseState(s, status);
        p.setProperty("closed", "true");
        p.setProperty("endedAt", Long.toString(System.currentTimeMillis()));
        writeState(p);
    }

    private Properties baseState(Session s, SessionStatus status) {
        Properties p = new Properties();
        p.setProperty("version", STATE_VERSION);
        p.setProperty("sessionId", s.getId());
        p.setProperty("startedAt", Long.toString(s.getStartedAt().toEpochMilli()));
        p.setProperty("updatedAt", Long.toString(System.currentTimeMillis()));
        p.setProperty("status", status.wireValue());
        p.setProperty("release", resolveRelease());
        putIfPresent(p, "environment", config.getEnvironment());
        putIfPresent(p, "sdkName", config.getSdkName());
        putIfPresent(p, "sdkVersion", config.getSdkVersion());
        putIfPresent(p, "platform", config.getPlatform());
        return p;
    }

    private Properties readState() {
        if (statePath == null || !Files.exists(statePath)) return null;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(statePath)) {
            p.load(in);
            if (!isValidState(p)) {
                removeState();
                return null;
            }
            return p;
        } catch (Throwable t) {
            removeState();
            return null;
        }
    }

    private void writeState(Properties p) {
        if (statePath == null) return;
        try {
            Path parent = statePath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = statePath.resolveSibling(statePath.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, "AllStak session recovery state");
            }
            Files.move(tmp, statePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // fail-open
        }
    }

    private void removeState() {
        if (statePath == null) return;
        try {
            Files.deleteIfExists(statePath);
        } catch (IOException ignored) {
            // ignore
        }
    }

    private static boolean isValidState(Properties p) {
        String status = p.getProperty("status");
        return STATE_VERSION.equals(p.getProperty("version"))
                && p.getProperty("sessionId") != null
                && parseLong(p.getProperty("startedAt"), -1) > 0
                && parseLong(p.getProperty("updatedAt"), -1) > 0
                && (SessionStatus.OK.wireValue().equals(status)
                || SessionStatus.ERRORED.wireValue().equals(status)
                || SessionStatus.CRASHED.wireValue().equals(status)
                || SessionStatus.ABNORMAL.wireValue().equals(status));
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void putIfPresent(Properties p, String key, String value) {
        if (value != null) p.setProperty(key, value);
    }

    public static Path defaultStatePath(AllStakConfig config) {
        String release = config.getRelease() != null ? config.getRelease() : config.getSdkVersion();
        String safe = release == null ? "default" : release.replaceAll("[^a-zA-Z0-9._-]", "_");
        return Path.of(System.getProperty("java.io.tmpdir"), "allstak-session-state", "session-" + safe + ".properties");
    }
}
