package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.model.RequestContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class AllStakCacheSupport {
    private AllStakCacheSupport() {}

    static void captureCacheSpan(AllStakClient client, String operation, String cacheName, Object key,
                                 long startMs, Throwable error) {
        try {
            RequestContext req = AllStakClient.getRequestContext();
            String traceId = req != null && req.getTraceId() != null ? req.getTraceId() : UUID.randomUUID().toString();
            String requestId = req != null ? req.getRequestId() : null;
            long endMs = System.currentTimeMillis();
            Map<String, String> tags = new LinkedHashMap<>();
            tags.put("span.kind", "cache.client");
            tags.put("cache.system", "spring-cache");
            tags.put("cache.operation", operation);
            if (cacheName != null) tags.put("cache.name", cacheName);
            if (key != null) tags.put("cache.key_hash", hashKey(key));
            if (requestId != null) tags.put("request.id", requestId);
            if (error != null) {
                tags.put("error.type", error.getClass().getName());
                if (error.getMessage() != null) tags.put("error.message", error.getMessage());
            }
            client.captureSpan(traceId, randomSpanId(), null, "cache." + operation,
                    cacheName != null ? cacheName : "cache", error == null ? "ok" : "error",
                    endMs - startMs, startMs, endMs,
                    client.getConfig().getServiceName(), client.getConfig().getEnvironment(), tags);
        } catch (Exception ignored) {
            // Cache instrumentation must never affect application cache calls.
        }
    }

    static String hashKey(Object key) {
        if (key == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(key).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 8 && i < hash.length; i++) out.append(String.format("%02x", hash[i]));
            return out.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static String randomSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
