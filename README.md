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
  <version>0.4.0</version>
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

Each integration below is attached automatically by the starter — you
register your normal bean and get the telemetry with no per-call code.
The starter detects the library on your classpath (`@ConditionalOnClass`)
and wires the AllStak interceptor onto the relevant client/factory bean.

| If your app uses…                              | AllStak activates                                | Gate (default ON)              |
|------------------------------------------------|--------------------------------------------------|--------------------------------|
| Spring MVC / Servlet                           | Inbound HTTP capture + scope per request         | `capture-http-requests`        |
| `RestTemplate` / `WebClient`                   | Outbound HTTP span + trace headers               | (on with web)                  |
| OkHttp                                         | Interceptor attached to every `OkHttpClient` bean| `capture-ok-http`              |
| Apache HttpClient 5                            | Interceptors attached to every `HttpClientBuilder` bean | `capture-apache-http`   |
| OpenFeign                                      | `RequestInterceptor` bean (trace-header injection)| `capture-feign`               |
| Project Reactor                                | Scope propagation across thread hops             | `capture-reactor`              |
| `@Scheduled`                                   | Cron check-ins, error capture                    | `capture-scheduled`            |
| Quartz                                         | `JobListener` attached to every `Scheduler` bean | `capture-quartz`               |
| Spring Kafka (`DefaultKafka*Factory`)          | Producer + consumer interceptor appended to factories | `capture-kafka`           |
| Spring Security                                | Authenticated principal → scope user             | `capture-security-user`        |
| JDBC `DataSource`                              | Span per query                                   | `capture-db-queries`           |
| Spring Data Redis (Lettuce)                    | Tracing wired into auto-configured `ClientResources` | `capture-lettuce`          |
| Spring Data MongoDB driver                     | Command listener added to the Mongo client       | `capture-mongodb`              |
| Spring Cache                                   | Span per get / put / evict                       | `capture-spring-cache`         |
| graphql-java / Spring GraphQL                  | `Instrumentation` bean → span per operation      | `capture-graphql`              |
| gRPC (`io.grpc.ServerBuilder` bean)            | Server interceptor registered (inbound trace context) | `capture-grpc`            |
| Logback / Log4j2                               | Logs → events / breadcrumbs                      | `capture-logs`                 |
| Java Flight Recorder                           | Continuous CPU profile (opt-in)                  | `enable-profiling` (off)       |

Disable any one with its gate property set to `false`
(e.g. `allstak.capture-kafka=false`); everything is on by default when
its library is present.

> **Note on Kafka / Lettuce / MongoDB.** Auto-attachment for these uses
> the Spring integration beans (`spring-kafka`, `spring-data-redis`,
> `spring-data-mongodb`). When you use the raw `kafka-clients`,
> `lettuce-core`, or MongoDB driver directly — without the matching Spring
> integration — wire the interceptor at the point you build the client
> using the helper documented on each module's class.

### Helper-based modules (manual wiring)

These ship as instrumentation helpers rather than auto-attached beans,
because there is no safe, side-effect-free attachment point Spring can
hook into for them. Call the helper where you build the client / handle
the message:

| Module             | How to wire                                                            |
|--------------------|------------------------------------------------------------------------|
| gRPC client        | `channelBuilder.intercept(new AllStakGrpcClientInterceptor())`         |
| Jedis              | `AllStakJedisInstrumentation.timed("GET", () -> jedis.get(key))`       |
| R2DBC              | `ProxyConnectionFactory.builder(cf).listener(new AllStakR2dbcProxyListener())` |
| JMS                | `AllStakJmsTrace.stamp(msg)` / `AllStakJmsTrace.harvest(msg)`          |
| RabbitMQ           | `AllStakRabbitTrace` on publish / consume                              |
| Kotlin coroutines  | `ScopeSnapshot` helper across suspension points                        |
| OpenTelemetry SDK  | `SpanProcessor` bridge into AllStak                                    |

### Plain Java (no Spring Boot)

```xml
<dependency>
  <groupId>sa.allstak</groupId>
  <artifactId>allstak-java-core</artifactId>
  <version>0.4.0</version>
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
| `allstak.capture-ok-http` | Attaches the interceptor to `OkHttpClient` beans. |
| `allstak.capture-apache-http` | Attaches interceptors to `HttpClientBuilder` beans. |
| `allstak.capture-kafka` | Appends trace interceptors to Spring Kafka factories. |
| `allstak.capture-lettuce` | Wires Redis command tracing (Spring Data Redis). |
| `allstak.capture-mongodb` | Adds the Mongo command listener (Spring Data MongoDB). |
| `allstak.capture-graphql` | Exposes the graphql-java `Instrumentation` bean. |
| `allstak.capture-grpc` | Registers the gRPC server interceptor on `ServerBuilder` beans. |

Every `capture-*` gate defaults to `true` and only takes effect when the
matching library is present. Set any to `false` to opt out.

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
