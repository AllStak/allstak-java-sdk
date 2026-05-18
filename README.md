# allstak-java-sdk

**Production error tracking + structured logs for Spring Boot apps. Auto-configures in one dependency.**

[![Maven Central](https://img.shields.io/maven-central/v/sa.allstak/allstak-java-core.svg)](https://central.sonatype.com/artifact/sa.allstak/allstak-java-core)
[![CI](https://github.com/allstak-io/allstak-java-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/allstak-io/allstak-java-sdk/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Official AllStak SDK for Java and Spring Boot — captures exceptions, structured logs, HTTP requests, database queries, and distributed traces with a single auto-configured starter.

## Dashboard

View captured events live at [app.allstak.sa](https://app.allstak.sa).

![AllStak dashboard](https://app.allstak.sa/images/dashboard-preview.png)

## Features

- Exception and `Thread.UncaughtExceptionHandler` capture
- Structured logs via SLF4J bridge
- Spring Boot auto-configuration (servlet filter, `RestTemplate` / `WebClient` interceptors)
- JDBC `DataSource` wrapper for DB query telemetry
- Distributed tracing with span context propagation
- Cron heartbeats and outbound HTTP capture
- Java 17+ / Spring Boot 3.x

## Installation

### Maven

```xml
<dependency>
  <groupId>sa.allstak</groupId>
  <artifactId>allstak-spring-boot-starter</artifactId>
  <version>0.1.3</version>
</dependency>
```

Plain Java (no Spring):

```xml
<dependency>
  <groupId>sa.allstak</groupId>
  <artifactId>allstak-java-core</artifactId>
  <version>0.1.3</version>
</dependency>
```

### Gradle

```groovy
implementation 'sa.allstak:allstak-spring-boot-starter:0.1.3'
// or, without Spring:
implementation 'sa.allstak:allstak-java-core:0.1.3'
```

## Quick Start

> Create a project at [app.allstak.sa](https://app.allstak.sa) to get your API key.

### Spring Boot

Add to `application.yml`:

```yaml
allstak:
  api-key: ${ALLSTAK_API_KEY}
  environment: production
  release: myapp@1.0.0
  service-name: myapp-api
```

Then capture a test exception anywhere in your app:

```java
import dev.allstak.AllStak;

AllStak.captureException(new RuntimeException("test: hello from allstak-java"));
```

Run the app — the test error appears in your dashboard within seconds.

### Plain Java

```java
import dev.allstak.AllStak;
import dev.allstak.AllStakConfig;

AllStak.init(AllStakConfig.builder()
    .apiKey(System.getenv("ALLSTAK_API_KEY"))
    .environment("production")
    .release("myapp@1.0.0")
    .serviceName("myapp-api")
    .build());

AllStak.captureException(new RuntimeException("test: hello from allstak-java"));
```

## Get Your API Key

1. Sign up at [app.allstak.sa](https://app.allstak.sa)
2. Create a project
3. Copy your API key from **Project Settings → API Keys**
4. Export it as `ALLSTAK_API_KEY` or pass it to `AllStakConfig.builder().apiKey(...)`

## Configuration

| Option | Type | Required | Default | Description |
|---|---|---|---|---|
| `apiKey` | `String` | yes | — | Project API key (`ask_live_…`) |
| `environment` | `String` | no | — | Deployment env |
| `release` | `String` | no | — | Version / git SHA |
| `serviceName` | `String` | no | — | Logical service identifier |
| `flushIntervalMs` | `long` | no | `2000` | Background flush cadence |
| `bufferSize` | `int` | no | `500` | Max items per buffer |
| `autoBreadcrumbs` | `boolean` | no | `true` | Auto-capture logs/HTTP breadcrumbs |
| `maxBreadcrumbs` | `int` | no | `50` | Ring buffer size |
| `debug` | `boolean` | no | `false` | Verbose SDK logging |

The ingest endpoint defaults to `https://api.allstak.sa` and can be overridden
with `allstak.host`, `ALLSTAK_HOST`, or `AllStakConfig.builder().host(...)` for
dev and self-hosted deployments.

## Example Usage

Capture an exception with metadata:

```java
AllStak.captureException(new RuntimeException("Payment failed"),
    Map.of("orderId", "ORD-42"));
```

Send a structured log:

```java
AllStak.captureLog("info", "Order processed", Map.of("orderId", "ORD-123"));
```

Set user context:

```java
AllStak.setUser(new UserContext("u_42", "alice@example.com"));
AllStak.setTag("region", "eu-west-1");
```

## Fail-Open Reliability

AllStak telemetry is best-effort. Runtime capture APIs enqueue into bounded
background workers and drop telemetry before harming the host process. If
AllStak ingest is down, slow, rate-limiting, under maintenance, or unreachable,
customer requests continue normally.

- Spring servlet filters and interceptors never wait on AllStak transport.
- Error, heartbeat, span, log, request, and DB telemetry use bounded queues or
  bounded background delivery.
- DNS, connection, timeout, 429, 500, and 503 failure modes are covered by
  automated fail-open tests.
- Shutdown flush is bounded and optional.

## Production Endpoint

Production endpoint: `https://api.allstak.sa`. Override with `allstak.host`,
`ALLSTAK_HOST`, or `AllStakConfig.builder().host(...)`.

## Links

- Documentation: https://docs.allstak.sa
- Dashboard: https://app.allstak.sa
- Source: https://github.com/allstak-io/allstak-java-sdk

## License

MIT © AllStak

## Production readiness

### Install

`Maven coordinates: sa.allstak:allstak-java-core and sa.allstak:allstak-spring-boot-starter`

### Quick Start

Use the minimal setup shown above in this README, set an AllStak API key through environment/configuration, and verify telemetry in a non-production project before enabling it for users. Do not hardcode API keys in source code.

### Configuration

Configure the API key, ingest host, environment, release, service name, sample rates, and optional capture settings explicitly for each deployment. Default production host is `https://api.allstak.sa` unless this SDK documents otherwise.

### Environment Variables

Prefer environment variables for secrets and deployment-specific values: `ALLSTAK_API_KEY`, `ALLSTAK_HOST`, `ALLSTAK_ENVIRONMENT`, `ALLSTAK_RELEASE`, and SDK-specific build/source-map tokens where applicable. Client-side frameworks must only expose public client keys using their framework-specific public env var conventions.

### Framework Compatibility

Java 17+, Spring Boot 3.x. Local Maven verification passed, but Maven Central publication and live dashboard proof are pending.

### What Data Is Captured

Depending on the SDK and enabled integrations, AllStak can capture exceptions, logs, breadcrumbs, HTTP request metadata, traces/spans, release/environment tags, user context supplied by the application, cron/job heartbeat status, and source-map artifact metadata. Body/header capture is optional where supported and should stay disabled unless explicitly needed.

### Privacy / PII / Redaction

Do not send secrets, passwords, tokens, payment data, national IDs, or raw request/response bodies unless the SDK documentation for this package explicitly says the field is redacted and the behavior has been verified in your app. Authorization, cookie, token, password, secret, API key, and similar fields should be masked by default where capture is implemented. Add `beforeSend`/filter hooks or equivalent application-side scrubbing for domain-specific PII.

### Production Safety

The SDK must fail open: telemetry failures must not crash or materially block the host application. Keep queues bounded, retries bounded, debug logging off in production, and capture rates conservative until overhead is measured in your application. Live dashboard certification was **not verified** in the 2026-05-17 release-gate audit because live credentials were not available.

### Troubleshooting

If telemetry is missing, verify the package version, API key, ingest host, environment, release, network access to `https://api.allstak.sa`, sampling settings, framework integration order, and whether the SDK is disabled after an auth failure. For source maps, verify release/dist values and artifact upload responses.

### Release / Source Map Setup

JVM source map support is not applicable; Java stack frame grouping must be verified in dashboard certification.

### Version Compatibility

Keep the package manifest version, runtime SDK version constant, changelog entry, git tag, and registry version aligned. Do not publish from a dirty checkout.

### Known Limitations

Maven Central publication was not found in the audit. Treat install snippets as pending until artifacts are visible on Maven Central. Live dashboard proof, performance overhead, retry-storm behavior, and full production hardening must be revalidated before claiming production-stable readiness.

### Stability Status

Current status: **beta, publication pending**. This SDK is not production-stable unless a later certification report explicitly says so with live dashboard evidence.

