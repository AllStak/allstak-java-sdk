package dev.allstak.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.allstak.internal.SdkLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * HTTP transport layer for sending payloads to AllStak backend.
 * Handles serialization, timeouts, retries with exponential backoff, and fail-safe behavior.
 */
public final class HttpTransport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;

    // Set to true when a 401 is received — disables all further sends
    private volatile boolean disabled = false;

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

        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            SdkLogger.debug("Failed to serialize payload for {}: {}", path, e.getMessage());
            return false;
        }

        SdkLogger.debug("Sending to {}{}: {}", baseUrl, path, body);

        // Delay to apply before the NEXT attempt. When a server sends a valid
        // Retry-After header on a 429/503 we honor it instead of the fixed
        // exponential backoff schedule; otherwise this stays negative and we
        // fall back to RetryPolicy.delayForAttempt(...).
        long retryAfterOverrideMs = -1;

        for (int attempt = 0; attempt < RetryPolicy.maxAttempts(); attempt++) {
            long delay = retryAfterOverrideMs >= 0
                    ? retryAfterOverrideMs
                    : RetryPolicy.delayForAttempt(attempt);
            retryAfterOverrideMs = -1; // consume; default back to schedule
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

                if (RetryPolicy.isRetryable(status)) {
                    // Honor Retry-After (429/503) when the server provides a
                    // valid value; otherwise fall back to the backoff schedule.
                    Optional<String> retryAfter = response.headers().firstValue("Retry-After");
                    if (retryAfter.isPresent()) {
                        Duration parsed = RetryPolicy.parseRetryAfter(retryAfter.get(), Instant.now());
                        if (!parsed.isZero()) {
                            retryAfterOverrideMs = parsed.toMillis();
                            SdkLogger.debug("Retryable error {} for {} — honoring Retry-After {}ms — attempt {}/{}",
                                    status, path, retryAfterOverrideMs, attempt + 1, RetryPolicy.maxAttempts());
                            continue;
                        }
                    }
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
        return false;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
