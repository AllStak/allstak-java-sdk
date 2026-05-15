package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot application demonstrating AllStak Java SDK integration.
 *
 * The AllStak Spring Boot Starter auto-configures the SDK when
 * {@code allstak.api-key} is set in application.yml or via environment variable.
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
