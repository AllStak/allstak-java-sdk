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
        assertThat(masked.get("password")).isEqualTo("[MASKED]");
        assertThat(masked.get("token")).isEqualTo("[MASKED]");
        assertThat(masked.get("api_key")).isEqualTo("[MASKED]");
        assertThat(masked.get("authorization")).isEqualTo("[MASKED]");
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
        assertThat(masked.get("PASSWORD")).isEqualTo("[MASKED]");
        assertThat(masked.get("Token")).isEqualTo("[MASKED]");
        assertThat(masked.get("SECRET")).isEqualTo("[MASKED]");
    }

    // =========================================================================
    // Value-pattern scrubbing
    // =========================================================================

    @Test
    void scrubValue_redactsLuhnValidCreditCard() {
        // 4242 4242 4242 4242 is a canonical Luhn-valid test PAN.
        assertThat(DataMasker.scrubValue("card 4242 4242 4242 4242 charged", false))
                .isEqualTo("card [REDACTED] charged");
        // Hyphen separators and a bare run both work when Luhn passes.
        assertThat(DataMasker.scrubValue("4242-4242-4242-4242", false)).isEqualTo("[REDACTED]");
        assertThat(DataMasker.scrubValue("4242424242424242", false)).isEqualTo("[REDACTED]");
    }

    @Test
    void scrubValue_preservesLuhnInvalidDigitRuns() {
        // A 16-digit run that FAILS Luhn must survive (order id / sequence).
        assertThat(DataMasker.scrubValue("order 4242424242424243 placed", false))
                .isEqualTo("order 4242424242424243 placed");
        // Epoch-style 13-digit timestamp must survive.
        assertThat(DataMasker.scrubValue("ts=1716998400000", false)).isEqualTo("ts=1716998400000");
    }

    @Test
    void scrubValue_preservesLuhnValidRunWithoutCardIin() {
        // 975512425378291 is a 15-digit nanoTime-style id. It can pass Luhn yet
        // starts with "97" — not a card IIN — so it must NOT be redacted. This
        // is the over-redaction guard: generic numeric ids stay intact.
        String marker = "log-only-marker-975512425378291";
        assertThat(DataMasker.scrubValue(marker, false)).isEqualTo(marker);
    }

    @Test
    void scrubValue_creditCardAlwaysScrubbed_evenWithSendDefaultPii() {
        // (A) layer is ON regardless of the PII toggle.
        assertThat(DataMasker.scrubValue("pan 4242 4242 4242 4242", true))
                .isEqualTo("pan [REDACTED]");
    }

    @Test
    void scrubValue_redactsHyphenatedSsn_butNotBareNineDigits() {
        assertThat(DataMasker.scrubValue("ssn 123-45-6789 ok", false))
                .isEqualTo("ssn [REDACTED] ok");
        // Bare 9-digit number must NOT be touched (no hyphens required).
        assertThat(DataMasker.scrubValue("id 123456789 ok", false))
                .isEqualTo("id 123456789 ok");
        // SSN is always scrubbed regardless of sendDefaultPii.
        assertThat(DataMasker.scrubValue("123-45-6789", true)).isEqualTo("[REDACTED]");
    }

    @Test
    void scrubValue_redactsEmailAndIpv4_whenSendDefaultPiiFalse() {
        assertThat(DataMasker.scrubValue("contact leak@example.com now", false))
                .isEqualTo("contact [REDACTED] now");
        assertThat(DataMasker.scrubValue("from 203.0.113.99 hit", false))
                .isEqualTo("from [REDACTED] hit");
    }

    @Test
    void scrubValue_preservesEmailAndIpv4_whenSendDefaultPiiTrue() {
        assertThat(DataMasker.scrubValue("contact ok@example.com now", true))
                .isEqualTo("contact ok@example.com now");
        assertThat(DataMasker.scrubValue("from 198.51.100.7 hit", true))
                .isEqualTo("from 198.51.100.7 hit");
    }

    @Test
    void scrubValue_ipv4OctetValidation_doesNotMatchOutOfRange() {
        // 999.999.999.999 is not a valid IPv4 — octet validation rejects it.
        assertThat(DataMasker.scrubValue("ver 999.999.999.999 build", false))
                .isEqualTo("ver 999.999.999.999 build");
        // 256 is out of range; not redacted.
        assertThat(DataMasker.scrubValue("256.1.1.1", false)).isEqualTo("256.1.1.1");
    }

    @Test
    void scrubValue_nullBlankAndHugeStringsAreFailOpen() {
        assertThat(DataMasker.scrubValue(null, false)).isNull();
        assertThat(DataMasker.scrubValue("", false)).isEmpty();
        // Pathological input: a giant string is returned unchanged (skipped).
        String huge = "x".repeat(70_000) + " leak@example.com";
        assertThat(DataMasker.scrubValue(huge, false)).isEqualTo(huge);
    }

    @Test
    void maskMetadata_withFlag_scrubsValuesAndNestedMaps() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("note", "reach me at leak@example.com");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("password", "hunter2");          // key redaction
        meta.put("freeText", "card 4242 4242 4242 4242");
        meta.put("contexts", nested);             // nested value scrubbing
        meta.put("count", 42);                    // scalar preserved

        Map<String, Object> masked = DataMasker.maskMetadata(meta, false);

        assertThat(masked.get("password")).isEqualTo("[MASKED]");
        assertThat(masked.get("freeText")).isEqualTo("card [REDACTED]");
        assertThat(masked.get("count")).isEqualTo(42);
        @SuppressWarnings("unchecked")
        Map<String, Object> maskedNested = (Map<String, Object>) masked.get("contexts");
        assertThat(maskedNested.get("note")).isEqualTo("reach me at [REDACTED]");
    }

    @Test
    void maskBody_scrubsCreditCardInJsonValue() {
        String body = "{\"memo\":\"paid with 4242 4242 4242 4242\",\"amount\":10}";
        String masked = DataMasker.maskBody(body, "application/json", true);
        assertThat(masked).contains("[REDACTED]");
        assertThat(masked).doesNotContain("4242 4242 4242 4242");
        assertThat(masked).contains("\"amount\":10");
    }
}
