package dev.allstak.sample.controller;

import dev.allstak.AllStak;
import dev.allstak.model.JobHandle;
import dev.allstak.model.UserContext;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller with endpoints specifically for testing the AllStak SDK.
 */
@RestController
@RequestMapping("/test")
public class TestController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "sdk_initialized", String.valueOf(AllStak.isInitialized()));
    }

    @GetMapping("/throw-exception")
    public void throwException() {
        throw new RuntimeException("Intentional test exception from controller");
    }

    @GetMapping("/throw-npe")
    public void throwNpe() {
        String s = null;
        s.length(); // NPE
    }

    @PostMapping("/capture-error")
    public Map<String, String> captureError() {
        try {
            throw new IllegalStateException("Manually captured error for testing");
        } catch (Exception e) {
            AllStak.captureException(e, Map.of("source", "manual-test", "priority", "high"));
            return Map.of("result", "error captured");
        }
    }

    @PostMapping("/echo")
    public Map<String, Object> echo(@RequestBody Map<String, Object> body) {
        return Map.of("received", body.getOrDefault("safe", "ok"));
    }

    @PostMapping("/capture-log")
    public Map<String, String> captureLog(@RequestParam(defaultValue = "info") String level,
                                           @RequestParam(defaultValue = "Test log message") String message) {
        AllStak.captureLog(level, message, Map.of("endpoint", "/test/capture-log"));
        return Map.of("result", "log captured", "level", level);
    }

    @PostMapping("/capture-log-with-metadata")
    public Map<String, Object> captureLogWithMetadata() {
        Map<String, Object> metadata = Map.of(
                "orderId", "ORD-9821",
                "amount", 99.90,
                "currency", "USD",
                "password", "should-be-masked",
                "token", "secret-token-123"
        );
        AllStak.captureLog("warn", "Payment retry attempt", metadata);
        return Map.of("result", "log captured with metadata", "metadata_keys", metadata.keySet());
    }

    @PostMapping("/cron-job")
    public Map<String, String> simulateCronJob(@RequestParam(defaultValue = "daily-report") String slug,
                                                @RequestParam(defaultValue = "success") String status) {
        JobHandle handle = AllStak.startJob(slug);
        try {
            // Simulate work
            Thread.sleep(100);
            if ("failed".equalsIgnoreCase(status)) {
                throw new RuntimeException("Simulated cron job failure");
            }
            AllStak.finishJob(handle, "success", "Job completed: processed 42 records");
            return Map.of("result", "cron job succeeded", "slug", slug);
        } catch (RuntimeException e) {
            AllStak.finishJob(handle, "failed", e.getMessage());
            return Map.of("result", "cron job failed", "slug", slug, "error", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AllStak.finishJob(handle, "failed", "Interrupted");
            return Map.of("result", "interrupted");
        }
    }

    @PostMapping("/flush")
    public Map<String, String> flush() {
        AllStak.flush();
        return Map.of("result", "flushed");
    }
}
