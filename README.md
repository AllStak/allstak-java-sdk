# AllStak Java SDK

AllStak SDK for Java and Spring Boot. Captures exceptions, logs, inbound
and outbound HTTP requests, spans, JDBC and reactive DB queries,
scheduled jobs, message-queue work, cache calls, GraphQL operations,
release-health sessions, and continuous JVM profiles.

## One package, everything covered

```xml
<dependency>
  <groupId>sa.allstak</groupId>
  <artifactId>allstak-spring-boot-starter</artifactId>
  <version>0.1.5</version>
</dependency>
```

That is the entire dependency surface a Spring Boot consumer has to learn.

The starter pulls every AllStak instrumentation glue module transitively
(~190 KB total), but **none** of their underlying third-party libraries
(OkHttp, Apache HTTP 5, Kafka clients, MongoDB driver, Lettuce, Jedis,
Quartz, …). Each instrumentation lights up automatically the moment
your existing classpath already includes the matching library —
`@ConditionalOnClass` is the gate.

### What auto-activates when

| If your app uses…                       | AllStak activates                                       |
|-----------------------------------------|---------------------------------------------------------|
| Spring MVC / Servlet                    | Inbound HTTP capture + scope per request                |
| `RestTemplate` / `WebClient`            | Outbound HTTP span + trace headers                      |
| OkHttp                                  | Outbound HTTP span + trace headers                      |
| Apache HttpClient 5                     | Outbound HTTP span + trace headers                      |
| OpenFeign                               | Trace-header injection                                  |
| Project Reactor                         | Scope propagation across thread hops                    |
| `@Scheduled` / Quartz                   | Cron check-ins, error capture                           |
| Kafka clients                           | Producer + consumer trace propagation                   |
| Spring Security                         | Authenticated principal → scope user                    |
| JDBC `DataSource`                       | Span per query                                          |
| R2DBC                                   | Span per reactive query                                 |
| MongoDB driver                          | Span per command                                        |
| Lettuce / Jedis                         | Span per Redis command                                  |
| Spring Cache                            | Span per get / put / evict                              |
| graphql-java                            | Span per GraphQL operation                              |
| gRPC                                    | Client + server interceptors (trace propagation)        |
| Logback / Log4j2 / JUL                  | Logs → events / breadcrumbs                             |
| Java Flight Recorder (`enable-profiling=true`) | Continuous CPU profile                            |
| Kotlin coroutines                       | `ScopeSnapshot` helper                                  |
| OpenTelemetry SDK                       | `SpanProcessor` bridge into AllStak                     |

Disable any one with `allstak.capture-<name>=false`; everything is on
by default when its library is present.

### Plain Java (no Spring Boot)

```xml
<dependency>
  <groupId>sa.allstak</groupId>
  <artifactId>allstak-java-core</artifactId>
  <version>0.1.5</version>
</dependency>
```

Then wire individual instrumentation modules where your code constructs
the third-party client. See each module's README under the repo root
for a one-liner example.

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

## Contributing and Support

- Report bugs with the GitHub bug report template: https://github.com/AllStak/allstak-java-sdk/issues/new/choose
- Open pull requests using the checklist in [CONTRIBUTING.md](CONTRIBUTING.md).
- Report security vulnerabilities privately through [SECURITY.md](SECURITY.md).

## License

MIT
