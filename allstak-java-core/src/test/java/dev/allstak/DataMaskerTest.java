package dev.allstak;

import dev.allstak.masking.DataMasker;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DataMaskerTest {

    @Test
    void masksSensitiveMetadataKeys() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderId", "ORD-123");
        metadata.put("password", "secret123");
        metadata.put("token", "jwt-xxx");
        metadata.put("api_key", "key-123");
        metadata.put("authorization", "Bearer xxx");
        metadata.put("amount", 99.90);

        Map<String, Object> masked = DataMasker.maskMetadata(metadata);

        assertThat(masked.get("orderId")).isEqualTo("ORD-123");
        assertThat(masked.get("password")).isEqualTo("[REDACTED]");
        assertThat(masked.get("token")).isEqualTo("[REDACTED]");
        assertThat(masked.get("api_key")).isEqualTo("[REDACTED]");
        assertThat(masked.get("authorization")).isEqualTo("[REDACTED]");
        assertThat(masked.get("amount")).isEqualTo(99.90);
    }

    @Test
    void maskMetadata_nullReturnsNull() {
        assertThat(DataMasker.maskMetadata(null)).isNull();
    }

    @Test
    void maskMetadata_emptyReturnsEmpty() {
        Map<String, Object> empty = Map.of();
        assertThat(DataMasker.maskMetadata(empty)).isEmpty();
    }

    @Test
    void stripSensitiveQueryParams_removesQueryString() {
        assertThat(DataMasker.stripSensitiveQueryParams("/api/orders?token=abc&page=1"))
                .isEqualTo("/api/orders");
    }

    @Test
    void stripSensitiveQueryParams_preservesCleanPath() {
        assertThat(DataMasker.stripSensitiveQueryParams("/api/orders/123"))
                .isEqualTo("/api/orders/123");
    }

    @Test
    void stripSensitiveQueryParams_nullReturnsNull() {
        assertThat(DataMasker.stripSensitiveQueryParams(null)).isNull();
    }

    @Test
    void sensitiveHeaders_detected() {
        assertThat(DataMasker.isSensitiveHeader("Authorization")).isTrue();
        assertThat(DataMasker.isSensitiveHeader("Cookie")).isTrue();
        assertThat(DataMasker.isSensitiveHeader("X-AllStak-Key")).isTrue();
        assertThat(DataMasker.isSensitiveHeader("X-API-Key")).isTrue();
        assertThat(DataMasker.isSensitiveHeader("X-Auth-Token")).isTrue();
        assertThat(DataMasker.isSensitiveHeader("Content-Type")).isFalse();
        assertThat(DataMasker.isSensitiveHeader(null)).isFalse();
    }

    @Test
    void maskMetadata_caseInsensitive() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("PASSWORD", "secret");
        metadata.put("Token", "jwt");
        metadata.put("SECRET", "shh");

        // Keys are lowercased for matching
        Map<String, Object> masked = DataMasker.maskMetadata(metadata);
        assertThat(masked.get("PASSWORD")).isEqualTo("[REDACTED]");
        assertThat(masked.get("Token")).isEqualTo("[REDACTED]");
        assertThat(masked.get("SECRET")).isEqualTo("[REDACTED]");
    }
}
