package dev.allstak;

import dev.allstak.transport.RetryPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.*;

class RetryPolicyTest {

    @Test
    void maxAttemptsIsFive() {
        assertThat(RetryPolicy.maxAttempts()).isEqualTo(5);
    }

    @Test
    void firstAttemptIsImmediate() {
        assertThat(RetryPolicy.delayForAttempt(0)).isEqualTo(0);
    }

    @Test
    void subsequentAttemptsHaveBackoff() {
        // Attempt 1: ~1000-1500ms
        long delay1 = RetryPolicy.delayForAttempt(1);
        assertThat(delay1).isBetween(1000L, 1500L);

        // Attempt 2: ~2000-2500ms
        long delay2 = RetryPolicy.delayForAttempt(2);
        assertThat(delay2).isBetween(2000L, 2500L);

        // Attempt 3: ~4000-4500ms
        long delay3 = RetryPolicy.delayForAttempt(3);
        assertThat(delay3).isBetween(4000L, 4500L);

        // Attempt 4: ~8000-8500ms
        long delay4 = RetryPolicy.delayForAttempt(4);
        assertThat(delay4).isBetween(8000L, 8500L);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void serverErrorsAreRetryable(int status) {
        assertThat(RetryPolicy.isRetryable(status)).isTrue();
    }

    @Test
    void tooManyRequestsIsRetryable() {
        assertThat(RetryPolicy.isRetryable(429)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 422})
    void clientErrorsAreNotRetryable(int status) {
        assertThat(RetryPolicy.isRetryable(status)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 422})
    void clientErrorsDetected(int status) {
        assertThat(RetryPolicy.isClientError(status)).isTrue();
    }

    @Test
    void authErrorDetected() {
        assertThat(RetryPolicy.isAuthError(401)).isTrue();
        assertThat(RetryPolicy.isAuthError(403)).isFalse();
        assertThat(RetryPolicy.isAuthError(200)).isFalse();
    }

    // =========================================================================
    // Retry-After parsing (pure, no real sleeping)
    // =========================================================================

    private static final Instant NOW = Instant.parse("2026-05-26T12:00:00Z");

    @Test
    void parseRetryAfter_deltaSeconds() {
        assertThat(RetryPolicy.parseRetryAfter("2", NOW)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void parseRetryAfter_deltaSecondsZero() {
        // "0" is a valid integer => zero delay (caller treats ZERO as "fall back",
        // which is acceptable: no wait either way).
        assertThat(RetryPolicy.parseRetryAfter("0", NOW)).isEqualTo(Duration.ZERO);
    }

    @Test
    void parseRetryAfter_deltaSecondsWithWhitespace() {
        assertThat(RetryPolicy.parseRetryAfter("  120  ", NOW)).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void parseRetryAfter_httpDateInFuture_returnsDelta() {
        // 90 seconds after NOW
        Instant target = NOW.plusSeconds(90);
        String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.ofInstant(target, ZoneOffset.UTC));
        assertThat(RetryPolicy.parseRetryAfter(httpDate, NOW)).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void parseRetryAfter_httpDateLiteral_returnsDelta() {
        // Fixed example from RFC; delta measured from a fixed "now".
        Instant now = Instant.parse("2015-10-21T07:27:00Z");
        String httpDate = "Wed, 21 Oct 2015 07:28:00 GMT"; // +60s
        assertThat(RetryPolicy.parseRetryAfter(httpDate, now)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void parseRetryAfter_httpDateInPast_returnsZero() {
        Instant target = NOW.minusSeconds(60);
        String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.ofInstant(target, ZoneOffset.UTC));
        assertThat(RetryPolicy.parseRetryAfter(httpDate, NOW)).isEqualTo(Duration.ZERO);
    }

    @Test
    void parseRetryAfter_null_returnsZero() {
        assertThat(RetryPolicy.parseRetryAfter(null, NOW)).isEqualTo(Duration.ZERO);
    }

    @Test
    void parseRetryAfter_empty_returnsZero() {
        assertThat(RetryPolicy.parseRetryAfter("", NOW)).isEqualTo(Duration.ZERO);
        assertThat(RetryPolicy.parseRetryAfter("   ", NOW)).isEqualTo(Duration.ZERO);
    }

    @Test
    void parseRetryAfter_garbage_returnsZero() {
        assertThat(RetryPolicy.parseRetryAfter("soon", NOW)).isEqualTo(Duration.ZERO);
        assertThat(RetryPolicy.parseRetryAfter("12.5", NOW)).isEqualTo(Duration.ZERO);
        assertThat(RetryPolicy.parseRetryAfter("not-a-date", NOW)).isEqualTo(Duration.ZERO);
    }

    @Test
    void parseRetryAfter_negativeSeconds_returnsZero() {
        assertThat(RetryPolicy.parseRetryAfter("-5", NOW)).isEqualTo(Duration.ZERO);
    }

    @Test
    void parseRetryAfter_overMax_clampsTo300s() {
        assertThat(RetryPolicy.parseRetryAfter("99999", NOW)).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void parseRetryAfter_httpDateOverMax_clampsTo300s() {
        Instant target = NOW.plusSeconds(10_000);
        String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.ofInstant(target, ZoneOffset.UTC));
        assertThat(RetryPolicy.parseRetryAfter(httpDate, NOW)).isEqualTo(Duration.ofSeconds(300));
    }
}
