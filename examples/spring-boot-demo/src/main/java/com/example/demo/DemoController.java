package com.example.demo;

import dev.allstak.AllStak;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Demo REST controller showcasing AllStak SDK capabilities:
 * error capture, tracing, database monitoring, and log capture.
 */
@RestController
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;

    @Autowired
    public DemoController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = new RestTemplate();

        // Initialize the demo table
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS demo_items ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                        + "name VARCHAR(255), "
                        + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + ")"
        );
        jdbcTemplate.execute(
                "MERGE INTO demo_items (id, name) KEY (id) VALUES (1, 'Widget'), (2, 'Gadget'), (3, 'Gizmo')"
        );
    }

    /**
     * GET / - Simple health check.
     */
    @GetMapping("/")
    public String index() {
        return "AllStak Spring Boot Demo";
    }

    /**
     * GET /error - Throws a RuntimeException to demonstrate error capture.
     * AllStak's servlet filter automatically captures unhandled exceptions.
     */
    @GetMapping("/error")
    public String triggerError() {
        throw new RuntimeException("Demo error: something went wrong in /error endpoint");
    }

    /**
     * GET /trace - Makes an outbound HTTP call to demonstrate tracing.
     * AllStak auto-instruments RestTemplate when the starter is on the classpath,
     * recording outbound HTTP telemetry (method, host, path, status, duration).
     */
    @GetMapping("/trace")
    public Map<String, Object> trace() {
        log.info("Making outbound HTTP call for tracing demo");

        String response;
        try {
            response = restTemplate.getForObject(
                    "https://httpbin.org/get", String.class
            );
        } catch (Exception e) {
            log.warn("Outbound call failed (expected in offline environments): {}", e.getMessage());
            response = "{\"error\": \"outbound call failed - this is expected if offline\"}";
        }

        return Map.of(
                "endpoint", "/trace",
                "outbound_url", "https://httpbin.org/get",
                "response_length", response != null ? response.length() : 0,
                "message", "Check AllStak dashboard for the traced HTTP request"
        );
    }

    /**
     * GET /db - Queries the H2 database to demonstrate database monitoring.
     * AllStak auto-instruments DataSource when the starter detects JDBC,
     * recording query text, duration, and row counts.
     */
    @GetMapping("/db")
    public Map<String, Object> database() {
        log.info("Executing database query for monitoring demo");

        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT id, name, created_at FROM demo_items ORDER BY id"
        );

        int count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM demo_items", Integer.class);

        return Map.of(
                "endpoint", "/db",
                "item_count", count,
                "items", items,
                "message", "Check AllStak dashboard for the captured SQL queries"
        );
    }

    /**
     * GET /log - Demonstrates log capture at various levels.
     * AllStak's Logback appender (auto-configured by the starter) forwards
     * log events to the AllStak dashboard.
     */
    @GetMapping("/log")
    public Map<String, String> logDemo() {
        log.debug("This is a DEBUG message from /log endpoint");
        log.info("This is an INFO message from /log endpoint");
        log.warn("This is a WARN message from /log endpoint");
        log.error("This is an ERROR message from /log endpoint");

        return Map.of(
                "endpoint", "/log",
                "message", "Logged messages at DEBUG, INFO, WARN, and ERROR levels. "
                        + "Check AllStak dashboard for captured logs."
        );
    }
}
