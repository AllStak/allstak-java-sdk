package dev.allstak.internal;

import dev.allstak.AllStakClient;

import java.util.Map;

/**
 * Global uncaught-exception capture for non-web / background threads.
 *
 * <p>Installs a {@link Thread.UncaughtExceptionHandler} as the JVM default via
 * {@link Thread#setDefaultUncaughtExceptionHandler}. Exceptions that escape any
 * thread (outside the Spring MVC {@code @ControllerAdvice} path) are captured,
 * a best-effort synchronous flush is attempted with a short timeout, and the
 * previously-installed default handler is then invoked so existing behavior
 * (crash reporting, JVM shutdown, etc.) is preserved — the SDK never swallows
 * the exception.
 *
 * <p>Install is idempotent: the previously-installed handler is stored, and a
 * double-install is a no-op. {@link #uninstall()} restores the prior handler.
 */
public final class UncaughtExceptionCapture implements Thread.UncaughtExceptionHandler {

    /** Mechanism marker for events captured via the global handler (handled=false). */
    public static final String MECHANISM_TYPE = "uncaughtexceptionhandler";

    private static final long FLUSH_TIMEOUT_MS = 2000;
    private static final Object LOCK = new Object();
    private static volatile UncaughtExceptionCapture installed;

    private final AllStakClient client;
    private final Thread.UncaughtExceptionHandler previous;

    private UncaughtExceptionCapture(AllStakClient client, Thread.UncaughtExceptionHandler previous) {
        this.client = client;
        this.previous = previous;
    }

    /**
     * Install the handler against the given client. Idempotent — if a handler is
     * already installed this is a no-op (the existing install is kept).
     *
     * @return true if a new handler was installed by this call, false if one was
     *         already present.
     */
    public static boolean install(AllStakClient client) {
        if (client == null) return false;
        synchronized (LOCK) {
            if (installed != null) {
                SdkLogger.debug("Uncaught exception handler already installed — skipping");
                return false;
            }
            Thread.UncaughtExceptionHandler prior = Thread.getDefaultUncaughtExceptionHandler();
            UncaughtExceptionCapture handler = new UncaughtExceptionCapture(client, prior);
            Thread.setDefaultUncaughtExceptionHandler(handler);
            installed = handler;
            SdkLogger.debug("Global uncaught exception handler installed");
            return true;
        }
    }

    /**
     * Restore the previously-installed default handler. No-op if nothing is
     * installed by this SDK, or if some other code replaced our handler in the
     * meantime (we never clobber a foreign handler).
     */
    public static void uninstall() {
        synchronized (LOCK) {
            UncaughtExceptionCapture current = installed;
            if (current == null) return;
            if (Thread.getDefaultUncaughtExceptionHandler() == current) {
                Thread.setDefaultUncaughtExceptionHandler(current.previous);
            }
            installed = null;
            SdkLogger.debug("Global uncaught exception handler uninstalled");
        }
    }

    /** Visible for testing. */
    public static boolean isInstalled() {
        return installed != null;
    }

    /** Visible for testing — the handler that was in place before our install. */
    Thread.UncaughtExceptionHandler previousHandler() {
        return previous;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("thread.name", thread != null ? thread.getName() : "unknown");
            metadata.put("mechanism.type", MECHANISM_TYPE);
            // Marks the event as unhandled, consistent with SDK conventions.
            metadata.put("mechanism.handled", false);
            client.captureException(throwable, "fatal", metadata);
            // Best-effort synchronous flush with a short bound so we don't hang
            // JVM shutdown if the network is slow/unreachable.
            flushWithTimeout();
        } catch (Throwable captureError) {
            SdkLogger.debug("Failed to capture uncaught exception: {}", captureError.getMessage());
        } finally {
            // Always delegate to the prior handler — never swallow.
            delegateToPrevious(thread, throwable);
        }
    }

    private void flushWithTimeout() {
        Thread flushThread = new Thread(() -> {
            try {
                client.flush();
            } catch (Throwable ignored) {
                // best-effort
            }
        }, "allstak-uncaught-flush");
        flushThread.setDaemon(true);
        flushThread.start();
        try {
            flushThread.join(FLUSH_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void delegateToPrevious(Thread thread, Throwable throwable) {
        try {
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                // No prior handler — mirror the JVM's default behavior so the
                // stack trace still reaches stderr.
                System.err.print("Exception in thread \""
                        + (thread != null ? thread.getName() : "unknown") + "\" ");
                throwable.printStackTrace(System.err);
            }
        } catch (Throwable ignored) {
            // Never let delegation failure mask the original throwable.
        }
    }
}
