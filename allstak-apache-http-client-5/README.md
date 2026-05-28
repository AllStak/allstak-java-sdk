# allstak-apache-http-client-5

Apache HttpClient 5 instrumentation. Single class implements both
`HttpRequestInterceptor` and `HttpResponseInterceptor`.

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-apache-http-client-5</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

## Use

### Spring Boot

Auto-wired by the starter. Disable with
`allstak.capture-apache-http=false`.

### Plain

```java
HttpClient client = HttpClients.custom()
    .addRequestInterceptorFirst(new AllStakApacheHttpInterceptor())
    .addResponseInterceptorLast(new AllStakApacheHttpInterceptor())
    .build();
```

## Captures

- Span (`http.client`) timed across the request/response pair.
- Breadcrumb with status code + duration.
- Outbound trace headers when the target matches
  `tracePropagationTargets`.
