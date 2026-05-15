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
  <version>0.1.2</version>
</dependency>
```

Plain Java (no Spring):

```xml
<dependency>
  <groupId>sa.allstak</groupId>
  <artifactId>allstak-java-core</artifactId>
  <version>0.1.2</version>
</dependency>
```

### Gradle

```groovy
implementation 'sa.allstak:allstak-spring-boot-starter:0.1.2'
// or, without Spring:
implementation 'sa.allstak:allstak-java-core:0.1.2'
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
