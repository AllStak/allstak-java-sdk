# allstak-jms

`jakarta.jms` message header helper.

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-jms</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

## Use

```java
// producer side
AllStakJmsTrace.stamp(message);

// consumer side
AllStakJmsTrace.harvest(message);
```

JMS providers can be picky about property names — we use
`allstak_trace_id` / `allstak_span_id`.
