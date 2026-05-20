package dev.allstak.masking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Masks sensitive data before it leaves the SDK.
 * Applies to: log metadata fields, HTTP request paths/headers.
 */
public final class DataMasker {

    private static final String MASKED = "[MASKED]";
    private static final String FILTERED = "[FILTERED]";
    private static final String REDACTED = "[REDACTED]";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
     * Mask sensitive keys in metadata map. Returns a new map with values masked.
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

    public static String maskBody(String body, String contentType) {
        if (body == null || body.isBlank()) return body;
        if (isJsonContent(contentType)) {
            try {
                JsonNode root = OBJECT_MAPPER.readTree(body);
                maskJson(root);
                return OBJECT_MAPPER.writeValueAsString(root);
            } catch (Exception ignored) {
                return maskText(body);
            }
        }
        return maskText(body);
    }

    public static boolean bodyNeedsRedaction(String body, String contentType) {
        if (body == null || body.isBlank()) return false;
        return !body.equals(maskBody(body, contentType));
    }

    private static void maskJson(JsonNode node) {
        if (node == null) return;
        if (node instanceof ObjectNode objectNode) {
            var fields = objectNode.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (isSensitiveKey(field.getKey())) {
                    objectNode.put(field.getKey(), REDACTED);
                } else {
                    maskJson(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode arrayNode) {
            for (JsonNode child : arrayNode) {
                maskJson(child);
            }
        }
    }

    private static String maskText(String body) {
        String masked = JSON_SENSITIVE_VALUE.matcher(body).replaceAll("$1\"" + REDACTED + "\"");
        return FORM_SENSITIVE_VALUE.matcher(masked).replaceAll("$1$2" + REDACTED);
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
