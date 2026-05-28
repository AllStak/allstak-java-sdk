# allstak-jedis

Manual Jedis instrumentation helper. Wrap critical commands explicitly
to get a span and a breadcrumb per call.

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-jedis</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

## Use

```java
String value = AllStakJedisInstrumentation.timed("GET",
    () -> jedis.get("user-42"));
```

## Why no auto-wrap?

Jedis has no tracing SPI, so the SDK can't transparently intercept
commands. Wrapping `JedisPool` would change resource semantics in
subtle ways; we leave that decision to the caller.
