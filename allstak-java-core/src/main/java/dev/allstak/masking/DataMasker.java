package dev.allstak.masking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks sensitive data before it leaves the SDK.
 *
 * <p>Two layers compose here:
 * <ol>
 *   <li><b>Key-name redaction</b> (existing) — keys/fields whose <em>name</em>
 *       looks sensitive (password/token/cookie/…) are blanked regardless of
 *       value. Applies to metadata maps and request/response bodies.</li>
 *   <li><b>Value-pattern scrubbing</b> (Sentry data-scrubbing parity) — PII
 *       that leaks into free-text <em>values</em> is matched by shape and
 *       replaced with {@value #REDACTED}. Layered:
 *       <ul>
 *         <li><b>Always</b>: Luhn-valid credit-card numbers and hyphenated
 *             US SSNs — high-risk financial/identity data never legitimately
 *             wanted in telemetry.</li>
 *         <li><b>Unless {@code sendDefaultPii}</b>: email addresses and
 *             validated IPv4 literals.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Conservative by design: credit-card-shaped digit runs are only redacted
 * when they pass the Luhn checksum (so order ids / timestamps survive), SSNs
 * require the hyphens (bare 9-digit numbers survive), and very large strings
 * are skipped to keep the wire path fast. Every public entry point is
 * fail-open — a scrubber error returns the input unchanged rather than throwing.
 */
public final class DataMasker {

    private static final String MASKED = "[MASKED]";
    private static final String FILTERED = "[FILTERED]";
    private static final String REDACTED = "[REDACTED]";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Strings longer than this are not value-scanned (returned unchanged but
     * still key-redacted via their containing map/body). Keeps the wire path
     * bounded on pathological input. Bodies are independently truncated by the
     * servlet filter before they reach here.
     */
    private static final int MAX_SCAN_CHARS = 64 * 1024;
    /** Cap on nested-map/list recursion when scrubbing metadata values. */
    private static final int MAX_METADATA_DEPTH = 8;

    // ── Value-pattern scrubbers (compiled once) ────────────────────────────

    /**
     * Candidate credit-card run: 13–19 digits allowing single space/hyphen
     * separators between digit groups. Bounded by non-digit edges so we don't
     * clip a longer numeric token. Luhn is verified on the matched digits
     * before redaction (see {@link #scrubCreditCards}).
     */
    private static final Pattern CREDIT_CARD_CANDIDATE = Pattern.compile(
            "(?<![\\d])(?:\\d[ -]?){12,18}\\d(?![\\d])");
    /** US SSN — hyphens REQUIRED (bare 9-digit numbers are intentionally not matched). */
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    /** Pragmatic email address. */
    private static final Pattern EMAIL = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    /** IPv4 with each octet validated to 0–255. */
    private static final Pattern IPV4 = Pattern.compile(
            "\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b");

    private static final Set<String> SENSITIVE_METADATA_KEYS = Set.of(
            "password", "secret", "token", "key", "authorization",
            "creditcard", "credit_card", "cardnumber", "card_number",
            "cvv", "ssn", "api_key", "apikey", "email", "phone",
            "phonenumber", "phone_number", "mobile", "nationalid",
            "national_id", "idnumber", "id_number", "otp", "otpcode",
            "otp_code", "passcode", "pin", "access_token", "refreshtoken",
            "refresh_token", "id_token", "jwt", "cookie", "set_cookie",
            "set-cookie", "iban", "pan", "cvc"
    );

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "x-allstak-key", "x-api-key", "x-auth-token"
    );

    private static final Pattern SENSITIVE_QUERY_PARAM = Pattern.compile(
            "(token|key|secret|password|auth|api_key)=([^&]*)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern JSON_SENSITIVE_VALUE = Pattern.compile(
            "(\"[^\"]*(?:authorization|cookie|set-cookie|password|passwd|pwd|passcode|otp|mfa|totp|pin|token|secret|api[-_]?key|access[-_]?token|refresh[-_]?token|id[-_]?token|jwt|bearer|client[-_]?secret|card|credit[-_]?card|cardnumber|phone|mobile|email|pan|iban|national[-_]?id|id[-_]?number|ssn|cvv|cvc)[^\"]*\"\\s*:\\s*)(\"(?:\\\\.|[^\"])*\"|\\d+(?:\\.\\d+)?|true|false|null)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FORM_SENSITIVE_VALUE = Pattern.compile(
            "(^|[&\\s])([^=&\\s]*(?:authorization|cookie|set-cookie|password|passwd|pwd|passcode|otp|mfa|totp|pin|token|secret|api[-_]?key|access[-_]?token|refresh[-_]?token|id[-_]?token|jwt|bearer|client[-_]?secret|card|credit[-_]?card|cardnumber|phone|mobile|email|pan|iban|national[-_]?id|id[-_]?number|ssn|cvv|cvc)[^=&\\s]*=)([^&\\s]+)",
            Pattern.CASE_INSENSITIVE
    );

    private DataMasker() {}

    /**
     * Mask sensitive keys in a metadata map (key-name redaction only). Retained
     * for back-compat; prefer {@link #maskMetadata(Map, boolean)} which also
     * applies value-pattern scrubbing.
     */
    public static Map<String, Object> maskMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return metadata;
        Map<String, Object> result = new LinkedHashMap<>(metadata.size());
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String keyLower = entry.getKey().toLowerCase();
            if (SENSITIVE_METADATA_KEYS.contains(keyLower)) {
                result.put(entry.getKey(), MASKED);
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * Mask a metadata map applying BOTH layers: sensitive-key redaction and
     * value-pattern PII scrubbing on string values (including nested maps and
     * lists, e.g. {@code tags}/{@code contexts}/{@code extras}). Numbers,
     * booleans, and other scalars pass through untouched. Fail-open: returns
     * the input on any error.
     *
     * @param sendDefaultPii when {@code true}, the email/IPv4 (B) scrubbers are
     *                       skipped (caller opted into PII); the always-on
     *                       credit-card/SSN (A) scrubbers still run.
     */
    public static Map<String, Object> maskMetadata(Map<String, Object> metadata, boolean sendDefaultPii) {
        if (metadata == null || metadata.isEmpty()) return metadata;
        try {
            return maskMetadataMap(metadata, sendDefaultPii, 0);
        } catch (Throwable t) {
            // Fail-open: never drop an event over a scrubbing error. The
            // key-only pass below still removes the highest-risk fields.
            try {
                return maskMetadata(metadata);
            } catch (Throwable ignored) {
                return metadata;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> maskMetadataMap(Map<String, Object> metadata, boolean sendDefaultPii, int depth) {
        Map<String, Object> result = new LinkedHashMap<>(metadata.size());
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (key != null && SENSITIVE_METADATA_KEYS.contains(key.toLowerCase())) {
                result.put(key, MASKED);
            } else {
                result.put(key, scrubMetadataValue(entry.getValue(), sendDefaultPii, depth));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object scrubMetadataValue(Object value, boolean sendDefaultPii, int depth) {
        if (value == null) return null;
        if (value instanceof String s) {
            return scrubValue(s, sendDefaultPii);
        }
        if (depth >= MAX_METADATA_DEPTH) return value;
        if (value instanceof Map<?, ?> map) {
            return maskMetadataMap((Map<String, Object>) map, sendDefaultPii, depth + 1);
        }
        if (value instanceof List<?> list) {
            java.util.List<Object> out = new java.util.ArrayList<>(list.size());
            for (Object item : list) out.add(scrubMetadataValue(item, sendDefaultPii, depth + 1));
            return out;
        }
        return value;
    }

    /**
     * Scrub PII patterns from a single free-text string value. Always redacts
     * Luhn-valid credit cards and hyphenated SSNs; redacts email + IPv4 only
     * when {@code sendDefaultPii} is {@code false}. Returns the input unchanged
     * if null, blank, too large to scan, or on any internal error (fail-open).
     */
    public static String scrubValue(String value, boolean sendDefaultPii) {
        if (value == null || value.isEmpty()) return value;
        if (value.length() > MAX_SCAN_CHARS) return value;
        try {
            String out = scrubCreditCards(value);
            out = SSN.matcher(out).replaceAll(REDACTED);
            if (!sendDefaultPii) {
                out = EMAIL.matcher(out).replaceAll(REDACTED);
                out = IPV4.matcher(out).replaceAll(REDACTED);
            }
            return out;
        } catch (Throwable t) {
            return value;
        }
    }

    /**
     * Redact only the credit-card-shaped digit runs that look like a real PAN:
     * a 13–19 digit run that BOTH passes the Luhn checksum AND begins with a
     * recognized card issuer prefix (IIN). The IIN gate is what keeps this
     * conservative — random 13–19 digit ids / epoch timestamps that happen to
     * satisfy Luhn (~1 in 10) are left intact unless they also start like a
     * Visa/Mastercard/Amex/Discover/JCB/Diners/UnionPay number.
     */
    private static String scrubCreditCards(String input) {
        Matcher m = CREDIT_CARD_CANDIDATE.matcher(input);
        StringBuilder sb = null;
        while (m.find()) {
            String candidate = m.group();
            String digits = candidate.replaceAll("[ -]", "");
            if (isCardLength(digits) && hasCardIin(digits) && isLuhnValid(candidate)) {
                if (sb == null) sb = new StringBuilder(input.length());
                m.appendReplacement(sb, Matcher.quoteReplacement(REDACTED));
            }
        }
        if (sb == null) return input;
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean isCardLength(String digits) {
        int n = digits.length();
        return n >= 13 && n <= 19;
    }

    /**
     * Whether the digit string begins with a known card Issuer Identification
     * Number prefix. Covers the major networks; deliberately strict so generic
     * numeric identifiers are not mistaken for cards.
     */
    private static boolean hasCardIin(String d) {
        // Visa
        if (d.startsWith("4")) return true;
        // Amex
        if (d.startsWith("34") || d.startsWith("37")) return true;
        // Diners Club
        if (d.startsWith("36") || d.startsWith("38") || d.startsWith("39")) return true;
        if (between(d, 3, "300", "305")) return true;
        // JCB 3528–3589
        if (between(d, 4, "3528", "3589")) return true;
        // Mastercard 51–55
        if (between(d, 2, "51", "55")) return true;
        // Mastercard 2221–2720
        if (between(d, 4, "2221", "2720")) return true;
        // Discover
        if (d.startsWith("6011") || d.startsWith("65")) return true;
        if (between(d, 3, "644", "649")) return true;
        // UnionPay
        if (d.startsWith("62")) return true;
        return false;
    }

    /** True when the first {@code len} chars of {@code d} are numerically in [lo, hi]. */
    private static boolean between(String d, int len, String lo, String hi) {
        if (d.length() < len) return false;
        String head = d.substring(0, len);
        return head.compareTo(lo) >= 0 && head.compareTo(hi) <= 0;
    }

    /**
     * Luhn checksum over the digits of {@code candidate} (separators ignored).
     * Length must be 13–19 digits to count as a card; anything else fails.
     */
    static boolean isLuhnValid(String candidate) {
        int sum = 0;
        int digits = 0;
        boolean alternate = false;
        for (int i = candidate.length() - 1; i >= 0; i--) {
            char c = candidate.charAt(i);
            if (c == ' ' || c == '-') continue;
            if (c < '0' || c > '9') return false;
            int d = c - '0';
            digits++;
            if (alternate) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            alternate = !alternate;
        }
        if (digits < 13 || digits > 19) return false;
        return sum % 10 == 0;
    }

    /**
     * Strip sensitive query parameters from a URL path.
     */
    public static String stripSensitiveQueryParams(String path) {
        if (path == null) return null;
        int queryStart = path.indexOf('?');
        if (queryStart < 0) return path;
        // Strip everything after ? to be safe — query params should not be logged
        return path.substring(0, queryStart);
    }

    /**
     * Check if an HTTP header name is sensitive and should be filtered.
     */
    public static boolean isSensitiveHeader(String headerName) {
        if (headerName == null) return false;
        return SENSITIVE_HEADERS.contains(headerName.toLowerCase());
    }

    /**
     * Mask a captured HTTP body. Defaults to the conservative privacy posture
     * ({@code sendDefaultPii=false}) so the email/IPv4 value scrubbers run.
     */
    public static String maskBody(String body, String contentType) {
        return maskBody(body, contentType, false);
    }

    /**
     * Mask a captured HTTP body. Applies sensitive-key redaction and
     * value-pattern PII scrubbing on string leaves. The email/IPv4 (B)
     * scrubbers are skipped when {@code sendDefaultPii} is true (the caller
     * opted into PII); credit-card/SSN (A) scrubbing always runs.
     */
    public static String maskBody(String body, String contentType, boolean sendDefaultPii) {
        if (body == null || body.isBlank()) return body;
        if (isJsonContent(contentType)) {
            try {
                JsonNode root = OBJECT_MAPPER.readTree(body);
                maskJson(root, sendDefaultPii);
                return OBJECT_MAPPER.writeValueAsString(root);
            } catch (Exception ignored) {
                return maskText(body, sendDefaultPii);
            }
        }
        return maskText(body, sendDefaultPii);
    }

    public static boolean bodyNeedsRedaction(String body, String contentType) {
        if (body == null || body.isBlank()) return false;
        return !body.equals(maskBody(body, contentType));
    }

    private static void maskJson(JsonNode node, boolean sendDefaultPii) {
        if (node == null) return;
        if (node instanceof ObjectNode objectNode) {
            var fields = objectNode.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (isSensitiveKey(field.getKey())) {
                    objectNode.put(field.getKey(), REDACTED);
                } else {
                    JsonNode child = field.getValue();
                    if (child != null && child.isTextual()) {
                        String scrubbed = scrubValue(child.asText(), sendDefaultPii);
                        if (!scrubbed.equals(child.asText())) {
                            objectNode.put(field.getKey(), scrubbed);
                        }
                    } else {
                        maskJson(child, sendDefaultPii);
                    }
                }
            }
        } else if (node instanceof ArrayNode arrayNode) {
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode child = arrayNode.get(i);
                if (child != null && child.isTextual()) {
                    String scrubbed = scrubValue(child.asText(), sendDefaultPii);
                    if (!scrubbed.equals(child.asText())) {
                        arrayNode.set(i, com.fasterxml.jackson.databind.node.TextNode.valueOf(scrubbed));
                    }
                } else {
                    maskJson(child, sendDefaultPii);
                }
            }
        }
    }

    private static String maskText(String body, boolean sendDefaultPii) {
        String masked = JSON_SENSITIVE_VALUE.matcher(body).replaceAll("$1\"" + REDACTED + "\"");
        masked = FORM_SENSITIVE_VALUE.matcher(masked).replaceAll("$1$2" + REDACTED);
        return scrubValue(masked, sendDefaultPii);
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String normalized = key.toLowerCase().replaceAll("[^a-z0-9]", "");
        return SENSITIVE_METADATA_KEYS.contains(normalized)
                || normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("authorization")
                || normalized.contains("cookie")
                || normalized.contains("apikey")
                || normalized.contains("otp")
                || normalized.contains("totp")
                || normalized.contains("card")
                || normalized.contains("cvv")
                || normalized.contains("cvc")
                || normalized.contains("iban")
                || normalized.contains("nationalid")
                || normalized.contains("idnumber")
                || normalized.equals("email")
                || normalized.equals("phone")
                || normalized.equals("phonenumber")
                || normalized.equals("mobile");
    }

    private static boolean isJsonContent(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase();
        return lower.contains("application/json") || lower.contains("+json");
    }

    /**
     * Mask a SQL-like error message by stripping parameter values.
     */
    public static String sanitizeErrorMessage(String message) {
        if (message == null) return null;
        // Strip potential connection strings (jdbc:xxx://user:pass@host)
        return message.replaceAll("(://[^:]+:)[^@]+(@ )", "$1" + FILTERED + "$2");
    }
}
