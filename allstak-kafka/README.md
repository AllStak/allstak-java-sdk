# allstak-kafka

Kafka producer + consumer interceptors. Producer stamps every outbound
record with AllStak trace headers; consumer harvests them into the
active isolation scope so an error captured during processing inherits
the producer's trace.

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-kafka</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

## Use

### Plain

```properties
# producer.properties
interceptor.classes=dev.allstak.kafka.AllStakKafkaProducerInterceptor

# consumer.properties
interceptor.classes=dev.allstak.kafka.AllStakKafkaConsumerInterceptor
```

### Spring Boot

Spring Kafka users can add these to
`spring.kafka.producer.properties` /
`spring.kafka.consumer.properties` directly. (No auto-config — Spring
Kafka factories are too varied to monkey-patch safely.)

## Headers

- `x-allstak-trace-id`
- `x-allstak-span-id`

Producer never overwrites existing values (idempotent). Consumer copies
`x-allstak-trace-id` into the scope's `trace.id` tag.
