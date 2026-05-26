package dev.allstak.transport;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Truncated exponential backoff with jitter per SDK guidelines.
 * Schedule: immediate, 1s+jitter, 2s+jitter, 4s+jitter, 8s+jitter (5 attempts total).
 */
public final class RetryPolicy {

    private static final int MAX_ATTEMPTS = 5;
    private static final long[] BASE_DELAYS_MS = {0, 1000, 2000, 4000, 8000};
    private static final long MAX_JITTER_MS = 500;

    /** Upper bound on any {@code Retry-After} delay we will honor. */
    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(300);

    private RetryPolicy() {}

    public static int maxAttempts() {
        return MAX_ATTEMPTS;
    }

    public static long delayForAttempt(int attempt) {
        if (attempt < 0 || attempt >= MAX_ATTEMPTS) return 0;
        long base = BASE_DELAYS_MS[attempt];
        if (base == 0) return 0;
        long jitter = ThreadLocalRandom.current().nextLong(0, MAX_JITTER_MS + 1);
        return base + jitter;
    }

    public static boolean isRetryable(int statusCode) {
        // Retry on 5xx and 429; do NOT retry on 400, 401, 403, 422
        if (statusCode == 429) return true;
        return statusCode >= 500;
    }

    public static boolean isClientError(int statusCode) {
        return statusCode == 400 || statusCode == 401 || statusCode == 403 || statusCode == 422;
    }

    public static boolean isAuthError(int statusCode) {
        return statusCode == 401;
    }

    /**
     * Parses an HTTP {@code Retry-After} header into a delay {@link Duration}.
     *
     * <p>Two forms are accepted per RFC 7231 §7.1.3:
     * <ul>
     *   <li>delta-seconds: a non-negative integer (e.g. {@code "120"}).</li>
     *   <li>HTTP-date: an RFC 1123 date; the delay is the difference between
     *       that date and {@code now} (negative/past dates clamp to ZERO).</li>
     * </ul>
     *
     * <p>Absent, blank, or otherwise unparseable values return
     * {@link Duration#ZERO}, signalling the caller to fall back to the normal
     * exponential backoff schedule. The result is clamped to
     * {@link #MAX_RETRY_AFTER} (300s).
     *
     * @param header the raw {@code Retry-After} header value (may be null)
     * @param now    reference instant for HTTP-date deltas (must not be null)
     * @return the delay to wait, never null, never negative, never &gt; 300s
     */
    public static Duration parseRetryAfter(String header, Instant now) {
        if (header == null) {
            return Duration.ZERO;
        }
        String trimmed = header.trim();
        if (trimmed.isEmpty()) {
            return Duration.ZERO;
        }

        // Form 1: delta-seconds (a bare non-negative integer).
        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds < 0) {
                return Duration.ZERO;
            }
            return clamp(Duration.ofSeconds(seconds));
        } catch (NumberFormatException ignore) {
            // Not an integer — try the HTTP-date form below.
        }

        // Form 2: HTTP-date (RFC 1123, e.g. "Wed, 21 Oct 2015 07:28:00 GMT").
        try {
            Instant target = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(trimmed));
            Duration delta = Duration.between(now, target);
            if (delta.isNegative()) {
                return Duration.ZERO;
            }
            return clamp(delta);
        } catch (RuntimeException ignore) {
            // DateTimeParseException (and any other parse/conversion failure)
            // means we cannot honor it — fall back to the backoff schedule.
            return Duration.ZERO;
        }
    }

    private static Duration clamp(Duration d) {
        if (d.compareTo(MAX_RETRY_AFTER) > 0) {
            return MAX_RETRY_AFTER;
        }
        return d;
    }
}
