# allstak-profiling-jfr

Continuous CPU profiler backed by Java Flight Recorder. JFR ships with
every modern JDK, so this module doesn't bundle native libraries — the
trade-off vs async-profiler is slightly higher overhead (~1–2%) but
instant cross-platform support including Windows.

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-profiling-jfr</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

## Use

### Spring Boot

Opt in with `allstak.enable-profiling=true`. The starter starts and
stops the profiler alongside the application context.

### Plain

```java
AllStakJfrProfiler profiler = AllStakJfrProfiler.start();
// ... app runs ...
profiler.stop();
```

## What ships

- One profile chunk every 30 seconds, posted as JSON wrapping the JFR
  binary base64-encoded.
- Max retention 50 MB on disk; older chunks are dropped automatically.
