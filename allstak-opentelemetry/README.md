# allstak-opentelemetry

OpenTelemetry `SpanProcessor` that forwards finished OTel spans into
the AllStak transport — lets users keep their OTel agent (or manual
OTel SDK) and dual-write into AllStak without rewiring the
instrumentation.

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-opentelemetry</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

## Use

```java
SdkTracerProvider provider = SdkTracerProvider.builder()
    .addSpanProcessor(new AllStakOtelSpanProcessor())
    .build();
```

(No auto-config — the OTel SDK setup is user-owned.)
