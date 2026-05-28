# allstak-lettuce

Lettuce `Tracing` adapter — one span per Redis command. Command names
are captured; argument values are NOT (Redis keys frequently carry
PII).

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-lettuce</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

## Use

```java
ClientResources resources = ClientResources.builder()
    .tracing(AllStakLettuceTracing.create())
    .build();
RedisClient client = RedisClient.create(resources, redisUri);
```

(No auto-config — `ClientResources` is constructor-time wiring; users
own it.)
