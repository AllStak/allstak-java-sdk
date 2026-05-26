# AllStak Java SDK

AllStak SDK for Java and Spring Boot. Captures exceptions, logs, inbound and outbound HTTP requests, spans, JDBC telemetry, and scheduled job telemetry.

## Install

Spring Boot:

```xml
<dependency>
  <groupId>sa.allstak</groupId>
  <artifactId>allstak-spring-boot-starter</artifactId>
  <version>0.1.5</version>
</dependency>
```

Plain Java:

```xml
<dependency>
  <groupId>sa.allstak</groupId>
  <artifactId>allstak-java-core</artifactId>
  <version>0.1.5</version>
</dependency>
```

## Spring Boot setup

```yaml
allstak:
  api-key: ${ALLSTAK_API_KEY}
  environment: production
  release: ${ALLSTAK_RELEASE}
  service-name: checkout-api
```

The starter auto-registers servlet request capture, exception capture, log capture, `RestTemplate`, `WebClient`, scheduled job, and JDBC integrations when those components are present.

## Plain Java setup

```java
import dev.allstak.AllStak;
import dev.allstak.AllStakConfig;

AllStak.init(AllStakConfig.builder()
    .apiKey(System.getenv("ALLSTAK_API_KEY"))
    .environment("production")
    .release(System.getenv("ALLSTAK_RELEASE"))
    .serviceName("worker")
    .build());

AllStak.captureLog("info", "worker started");
AllStak.captureException(new RuntimeException("checkout failed"));
AllStak.flush();
AllStak.shutdown();
```

## Configuration

| Property | Description |
| --- | --- |
| `allstak.api-key` | Project API key. |
| `allstak.enabled` | Enables or disables auto-configuration. |
| `allstak.environment` | Deployment environment. |
| `allstak.release` | App version or commit SHA. |
| `allstak.service-name` | Logical service name. |
| `allstak.capture-http-requests` | Captures inbound requests. |
| `allstak.capture-exceptions` | Captures exceptions. |
| `allstak.capture-logs` | Captures logs. |
| `allstak.capture-db-queries` | Captures JDBC queries. |

## Privacy

The SDK redacts common sensitive headers and fields. Avoid putting secrets in custom metadata or log messages.

## Troubleshooting

- No events: confirm `ALLSTAK_API_KEY` is available to the JVM.
- Missing Spring telemetry: confirm the starter dependency is on the runtime classpath.
- Missing outbound traces: use Spring-managed `RestTemplate` or `WebClient` beans.

## License

MIT
