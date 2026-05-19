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
        assertThat(config.getRelease()).isNull();
    }
}
