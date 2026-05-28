# allstak-okhttp

OkHttp instrumentation for the AllStak Java SDK. Adds one span +
breadcrumb per HTTP call and injects AllStak trace headers on outbound
requests gated by `tracePropagationTargets`.

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-okhttp</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

(or import the `allstak-bom` and drop the `<version>` here).

## Use

### Spring Boot

`allstak-spring-boot-starter` auto-wires the interceptor as a
`@Bean dev.allstak.okhttp.AllStakOkHttpInterceptor` when this module
is on the classpath. Disable with
`allstak.capture-ok-http=false`.

### Plain

```java
OkHttpClient client = new OkHttpClient.Builder()
    .addInterceptor(new AllStakOkHttpInterceptor())
    .build();
```

## What it captures

- Per call: a span (`http.client`), a breadcrumb (`http`).
- Per call (when target matches `tracePropagationTargets`): three
  outbound headers — `x-allstak-trace-id`, `x-allstak-span-id`,
  `traceparent`.
- On 4xx/5xx or thrown exception, breadcrumb level escalates to
  `warn`/`error`.

## What it does *not* capture

- Request / response bodies — too much PII risk; use the platform's
  application-level capture if you really need them.
- Sensitive request headers (`Authorization`, `Cookie`, etc.) — those
  are scrubbed by the core `DataMasker` once the call lands on the
  ingest pipeline.
