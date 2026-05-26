package dev.allstak;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AllStakConfigTest {

    @Test
    void validConfigBuilds() {
        AllStakConfig config = AllStakConfig.builder()
                .apiKey("ask_live_test123")
                .environment("production")
                .release("v1.0.0")
                .build();

        assertThat(config.getApiKey()).isEqualTo("ask_live_test123");
        assertThat(config.getEnvironment()).isEqualTo("production");
        assertThat(config.getRelease()).isEqualTo("v1.0.0");
        assertThat(config.getFlushIntervalMs()).isEqualTo(5000);
        assertThat(config.getBufferSize()).isEqualTo(500);
        assertThat(config.isDebug()).isFalse();
    }

    @Test
    void hostIsAlwaysTheStaticIngestEndpoint() {
        AllStakConfig config = AllStakConfig.builder()
                .apiKey("test")
                .build();

        assertThat(config.getHost()).isEqualTo(AllStakConfig.INGEST_HOST);
    }

    @Test
    void nullApiKeyThrows() {
        assertThatThrownBy(() -> AllStakConfig.builder().build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    void blankApiKeyThrows() {
        assertThatThrownBy(() -> AllStakConfig.builder().apiKey("  ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    void zeroBufferSizeThrows() {
        assertThatThrownBy(() -> AllStakConfig.builder()
                .apiKey("test").bufferSize(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bufferSize");
    }

    @Test
    void zeroFlushIntervalThrows() {
        assertThatThrownBy(() -> AllStakConfig.builder()
                .apiKey("test").flushIntervalMs(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flushIntervalMs");
    }

    @Test
    void defaultValues() {
        AllStakConfig config = AllStakConfig.builder()
                .apiKey("test")
                .build();

        assertThat(config.getFlushIntervalMs()).isEqualTo(5000);
        assertThat(config.getBufferSize()).isEqualTo(500);
        assertThat(config.isDebug()).isFalse();
        assertThat(config.getEnvironment()).isEqualTo("production");
        // With auto-detection on (default), release is never empty: it resolves
        // to a git-describe value (in a source checkout) or the SDK_VERSION
        // fallback. It must not be null.
        assertThat(config.getRelease()).isNotNull();
    }

    // --- Release resolution precedence (explicit > env > detected > version) ---
    // These exercise the pure seamable resolver so they need no real repo.

    @Test
    void explicitReleaseAlwaysWins() {
        // Even with a "detected" value available, explicit wins.
        String r = AllStakConfig.resolveRelease("explicit-v1", true, () -> "detected-sha");
        assertThat(r).isEqualTo("explicit-v1");
    }

    @Test
    void detectedUsedWhenNoExplicitOrEnv() {
        // No explicit value; env vars are not set in the test JVM, so the
        // detected supplier wins over the version fallback.
        String r = AllStakConfig.resolveRelease(null, true, () -> "abc1234-dirty");
        assertThat(r).isEqualTo("abc1234-dirty");
    }

    @Test
    void fallsBackToSdkVersionWhenDetectionEmpty() {
        String r = AllStakConfig.resolveRelease(null, true, () -> null);
        assertThat(r).isEqualTo(AllStakConfig.SDK_VERSION);
    }

    @Test
    void fallsBackToSdkVersionWhenDetectorThrowsNothingCrashes() {
        // The resolver only sees the supplier result; a throwing git runner is
        // already swallowed inside ReleaseDetector.parse (covered separately).
        String r = AllStakConfig.resolveRelease(null, true, () -> "");
        assertThat(r).isEqualTo(AllStakConfig.SDK_VERSION);
    }

    @Test
    void optOutDisablesDetectionAndFallback() {
        String r = AllStakConfig.resolveRelease(null, false, () -> "detected-sha");
        assertThat(r).isNull();
    }

    @Test
    void optOutLeavesReleaseNullViaBuilder() {
        AllStakConfig config = AllStakConfig.builder()
                .apiKey("test")
                .autoDetectRelease(false)
                .build();
        // No explicit release, env vars unset in test JVM, detection off.
        assertThat(config.getRelease()).isNull();
    }
}
