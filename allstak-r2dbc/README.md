# allstak-r2dbc

R2DBC `ProxyExecutionListener` emitting a span per reactive query.

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-r2dbc</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

## Use

```java
ConnectionFactory wrapped = ProxyConnectionFactory.builder(raw)
    .listener(new AllStakR2dbcProxyListener())
    .build();
```
