package dev.allstak.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.allstak.internal.SdkLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP transport layer for sending payloads to AllStak backend.
 * Handles serialization, timeouts, retries with exponential backoff, and fail-safe behavior.
 */
public final class HttpTransport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final long DEFAULT_CIRCUIT_OPEN_MS = 30_000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;

    // Set to true when a 401 is received — disables all further sends
    private volatile boolean disabled = false;
    private volatile long circuitOpenUntilMs = 0;

    public HttpTransport(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    // Visible for testing
    HttpTransport(String baseUrl, String apiKey, HttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
        this.httpClient = httpClient;
    }

    public boolean isDisabled() {
        return disabled;
    }

    /**
     * Send a payload to the given endpoint path with retry logic.
     * Returns true if the send succeeded (202), false otherwise.
     */
    public boolean send(String path, Object payload) {
        if (disabled) {
            SdkLogger.debug("SDK is disabled (401 received) — dropping event for {}", path);
            return false;
        }
        if (System.currentTimeMillis() < circuitOpenUntilMs) {
            SdkLogger.debug("AllStak transport circuit open — dropping event for {}", path);
            return false;
        }

        // Serialize → parse to a generic tree → scrub the tree → reserialize.
        // The two-pass approach catches sensitive keys whether the caller
        // used a POJO (with @JsonProperty), a Map, or a nested mix. One
        // chokepoint here protects every telemetry type (errors, logs,
        // http, db, spans). Pure (no caller mutation), fail-open on
        // sanitizer error.
        String body;
        try {
            String rawJson = objectMapper.writeValueAsString(payload);
            try {
                Object tree = objectMapper.readValue(rawJson, Object.class);
                Object scrubbed = dev.allstak.masking.DataMasker.maskWire(tree);
                body = objectMapper.writeValueAsString(scrubbed);
            } catch (Exception sanErr) {
                SdkLogger.debug("Sanitizer failed for {} ({}); sending raw payload", path, sanErr.getMessage());
                body = rawJson;
            }
        } catch (Exception e) {
            SdkLogger.debug("Failed to serialize payload for {}: {}", path, e.getMessage());
            return false;
        }

        SdkLogger.debug("Sending to {}{}: {}", baseUrl, path, body);

        for (int attempt = 0; attempt < RetryPolicy.maxAttempts(); attempt++) {
            long delay = RetryPolicy.delayForAttempt(attempt);
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .header("Content-Type", "application/json")
                        .header("X-AllStak-Key", apiKey)
                        .timeout(REQUEST_TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                SdkLogger.debug("Response from {}{}: {} {}", baseUrl, path, status, response.body());

                if (status == 202) {
                    circuitOpenUntilMs = 0;
                    return true;
                }

                if (RetryPolicy.isAuthError(status)) {
                    SdkLogger.warn("Invalid API key — disabling SDK. Response: {}", response.body());
                    disabled = true;
                    return false;
                }

                if (RetryPolicy.isClientError(status)) {
                    SdkLogger.debug("Client error {} for {} — dropping event", status, path);
                    return false;
                }

                if (status == 429 || status == 503) {
                    openCircuit(response.headers().firstValue("Retry-After").orElse(null));
                    SdkLogger.debug("AllStak transport backed off after {} for {}", status, path);
                    return false;
                }

                if (RetryPolicy.isRetryable(status)) {
                    SdkLogger.debug("Retryable error {} for {} — attempt {}/{}", status, path, attempt + 1, RetryPolicy.maxAttempts());
                    continue;
                }

                // Unknown status — don't retry
                SdkLogger.debug("Unexpected status {} for {} — dropping event", status, path);
                return false;

            } catch (IOException e) {
                SdkLogger.debug("Network error for {} — attempt {}/{}: {}", path, attempt + 1, RetryPolicy.maxAttempts(), e.getMessage());
                // Retry on network errors
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                SdkLogger.debug("Unexpected error for {}: {}", path, e.getMessage());
                return false;
            }
        }

        SdkLogger.debug("All {} retry attempts exhausted for {} — discarding event", RetryPolicy.maxAttempts(), path);
        openCircuit(null);
        return false;
    }

    private void openCircuit(String retryAfterHeader) {
        long delayMs = DEFAULT_CIRCUIT_OPEN_MS;
        if (retryAfterHeader != null && !retryAfterHeader.isBlank()) {
            try {
                delayMs = Math.max(1000, Long.parseLong(retryAfterHeader.trim()) * 1000);
            } catch (NumberFormatException ignored) {
                delayMs = DEFAULT_CIRCUIT_OPEN_MS;
            }
        }
        circuitOpenUntilMs = System.currentTimeMillis() + delayMs;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
