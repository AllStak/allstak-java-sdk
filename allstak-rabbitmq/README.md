# allstak-rabbitmq

RabbitMQ AMQP `BasicProperties` header helper.

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-rabbitmq</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

## Use

```java
// producer
AMQP.BasicProperties stamped = AllStakRabbitTrace.withHeaders(props);
channel.basicPublish(exchange, key, stamped, body);

// consumer
AllStakRabbitTrace.harvest(delivery.getProperties());
```

Headers: `x-allstak-trace-id`, `x-allstak-span-id`.
