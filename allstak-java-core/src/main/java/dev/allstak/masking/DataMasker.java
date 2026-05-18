package dev.allstak.masking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Masks sensitive data before it leaves the SDK.
 *
 * <p>Conforms to the canonical AllStak SDK denylist defined in
 * docs/standards/sdk-platform-standards.md. The denylist matches every
 * other AllStak SDK (@allstak/js, @allstak/react-native, allstak-python,
 * allstak-ruby, AllStak .NET, allstak-go, allstak_flutter, @allstak/next,
 * @allstak/nestjs, @allstak/fastify, @allstak/otel).
 *
 * <p>Semantics:
 * <ul>
 *   <li>Case-insensitive substring match on map keys (so {@code
 *       stripe_api_key} matches {@code api_key}).</li>
 *   <li>Value replacement with {@code [REDACTED]} (key preserved).</li>
 *   <li>Recursion into nested {@code Map}s and {@code Collection}s.</li>
 *   <li>Cycle protection via an {@code IdentityHashMap} of visited
 *       containers.</li>
 *   <li>Pure: returns a sanitized copy; never mutates caller-owned
 *       structures.</li>
 * </ul>
 */
public final class DataMasker {

    /** New canonical sentinel — aligns with the rest of the AllStak ecosystem. */
    public static final String REDACTED = "[REDACTED]";

    /** Legacy sentinel; kept for backward compatibility with existing call sites. */
    @Deprecated
    public static final String MASKED = REDACTED;

    private static final String FILTERED = "[FILTERED]";

    /**
     * Canonical 25-term denylist. Case-insensitive substring match — the
     * key {@code stripe_api_key} matches {@code api_key}, {@code Bearer}
     * matches {@code bearer}, etc.
     */
    private static final List<String> DEFAULT_DENYLIST = List.of(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "password",
            "passwd",
            "pwd",
            "api_key",
            "apikey",
            "x-api-key",
            "x-allstak-key",
            "x-auth-token",
            "x-access-token",
            "token",
            "bearer",
            "jwt",
            "session",
            "sessionid",
            "session_id",
            "secret",
            "credit_card",
            "card_number",
            "cvv",
            "ssn",
            "csrf"
    );

    /** Legacy header set — preserved so {@link #isSensitiveHeader} keeps working. */
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "x-allstak-key", "x-api-key", "x-auth-token",
            "x-access-token", "proxy-authorization", "set-cookie"
    );

    private static final Pattern SENSITIVE_QUERY_PARAM = Pattern.compile(
            "(token|key|secret|password|auth|api_key)=([^&]*)",
            Pattern.CASE_INSENSITIVE
    );

    private DataMasker() {}

    /**
     * Returns {@code true} when {@code key} matches the canonical denylist.
     */
    public static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        for (String term : DEFAULT_DENYLIST) {
            if (lower.contains(term)) return true;
        }
        return false;
    }

    /**
     * Recursively scrub sensitive values out of {@code metadata}. Returns a
     * sanitized copy; the input map is never mutated. Top-level entry point
     * for the unified wire scrub.
     */
    public static Map<String, Object> maskMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return metadata;
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        Object out = walk(metadata, seen);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) out;
        return result;
    }

    /**
     * Recursively scrub an arbitrary wire payload. Works for top-level
     * {@code Map}s, {@code Collection}s, or scalars. Use this in the
     * transport just before JSON serialization. Pure (no caller mutation),
     * cycle-safe, fail-open on unexpected types.
     */
    public static Object maskWire(Object payload) {
        if (payload == null) return null;
        return walk(payload, new IdentityHashMap<>());
    }

    private static Object walk(Object value, IdentityHashMap<Object, Boolean> seen) {
        if (value == null) return null;
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> srcMap) {
            if (seen.put(srcMap, Boolean.TRUE) != null) return REDACTED; // cycle
            Map<String, Object> out = new LinkedHashMap<>(srcMap.size());
            for (Map.Entry<?, ?> entry : srcMap.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().toString();
                if (isSensitiveKey(key)) {
                    out.put(key, REDACTED);
                } else {
                    out.put(key, walk(entry.getValue(), seen));
                }
            }
            return out;
        }
        if (value instanceof Collection<?> coll) {
            if (seen.put(coll, Boolean.TRUE) != null) return REDACTED;
            List<Object> out = new ArrayList<>(coll.size());
            for (Object item : coll) {
                out.add(walk(item, seen));
            }
            return out;
        }
        if (value.getClass().isArray()) {
            if (seen.put(value, Boolean.TRUE) != null) return REDACTED;
            int len = java.lang.reflect.Array.getLength(value);
            List<Object> out = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                out.add(walk(java.lang.reflect.Array.get(value, i), seen));
            }
            return out;
        }
        // Unknown type (POJO, etc.) — pass through. Callers should serialize
        // these via Jackson, which we trust to honor @JsonIgnore.
        return value;
    }

    /**
     * Strip sensitive query parameters from a URL path.
     */
    public static String stripSensitiveQueryParams(String path) {
        if (path == null) return null;
        int queryStart = path.indexOf('?');
        if (queryStart < 0) return path;
        // Strip everything after ? to be safe — query params should not be logged.
        return path.substring(0, queryStart);
    }

    /**
     * Check if an HTTP header name is sensitive and should be filtered.
     */
    public static boolean isSensitiveHeader(String headerName) {
        if (headerName == null) return false;
        String lower = headerName.toLowerCase();
        if (SENSITIVE_HEADERS.contains(lower)) return true;
        // Also catch any header whose name matches the canonical denylist.
        return isSensitiveKey(headerName);
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
