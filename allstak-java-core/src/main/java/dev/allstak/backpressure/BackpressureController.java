package dev.allstak.backpressure;

import dev.allstak.internal.SdkLogger;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Adaptive sampler that downsamples telemetry when the host application
 * is showing signs of overload. The signal is upstream {@code 429} /
 * {@code 503} responses or transport-side queue overflow, both reported
 * via {@link #consumed(boolean, boolean)}. Each backpressure event halves
 * the effective sample-rate factor (down to a configurable floor); the
 * factor recovers by a step on every clean batch until it returns to 1.0.
 *
 * <p>Producers (transport, flush worker) call
 * {@link #scaleSampleRate(double)} to apply the factor on their own
 * static rate (e.g. {@code tracesSampleRate}). This way no AllStak
 * subsystem needs to know about the others.
 *
 * <p>Provides adaptive backpressure handling, with
 * a deliberately simple multiplicative-decrease / additive-increase
 * controller — the goal is to absorb transient saturation, not to be a
 * full PID loop.
 */
public final class BackpressureController {

    /** Factor floor — never sample below this. */
    public static final double MIN_FACTOR = 0.05;
    private static final double RECOVERY_STEP = 0.1;

    private final AtomicInteger backpressureEvents = new AtomicInteger();
    private volatile double factor = 1.0;

    public double currentFactor() { return factor; }

    /** Apply the current factor to a static rate. {@code null} passes through. */
    public Double scaleSampleRate(Double rate) {
        if (rate == null) return null;
        return Math.max(0.0, Math.min(1.0, rate * factor));
    }
    public double scaleSampleRate(double rate) {
        return Math.max(0.0, Math.min(1.0, rate * factor));
    }

    /**
     * Report the outcome of a flush attempt. {@code throttled=true} marks
     * a 429 or 503 from the server; {@code queueOverflow=true} marks a
     * client-side ring-buffer overflow. Either bumps the back-off.
     */
    public void consumed(boolean throttled, boolean queueOverflow) {
        if (throttled || queueOverflow) {
            backpressureEvents.incrementAndGet();
            factor = Math.max(MIN_FACTOR, factor / 2.0);
            SdkLogger.debug("Backpressure: factor halved to {} (throttled={}, overflow={})", factor, throttled, queueOverflow);
        } else if (factor < 1.0) {
            factor = Math.min(1.0, factor + RECOVERY_STEP);
        }
    }

    public int totalBackpressureEvents() { return backpressureEvents.get(); }
}
