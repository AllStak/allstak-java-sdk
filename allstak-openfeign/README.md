# allstak-openfeign

OpenFeign `RequestInterceptor` that injects AllStak trace headers on
every Feign outbound call (gated by `tracePropagationTargets`).

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-openfeign</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

## Use

### Spring Boot

Auto-wired as a `@Bean RequestInterceptor`; Spring Cloud OpenFeign
picks every such bean up automatically. Disable with
`allstak.capture-feign=false`.

### Plain

```java
Feign.builder()
    .requestInterceptor(new AllStakFeignInterceptor())
    .target(MyClient.class, "https://api.example.com");
```

## Captures

- Headers only (Feign doesn't expose the response on this hook). For
  full span coverage, pair this with `allstak-okhttp` or
  `allstak-apache-http-client-5` on the underlying Feign client.
