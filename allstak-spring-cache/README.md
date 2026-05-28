# allstak-spring-cache

Wraps every Spring `Cache` so get / put / evict emit AllStak spans.
Cache keys / values are NOT captured.

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-spring-cache</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

## Use

### Spring Boot

`allstak-spring-boot-starter` registers a `BeanPostProcessor` that
auto-wraps every `Cache` bean. Disable with
`allstak.capture-spring-cache=false`.

### Plain

```java
Cache wrapped = AllStakCacheSpanDecorator.wrap(originalCache);
```
