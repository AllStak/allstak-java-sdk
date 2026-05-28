# allstak-reactor

Project Reactor `onEachOperator` hook that propagates the active
AllStak isolation scope across thread hops (`Schedulers.parallel`,
`boundedElastic`, etc.).

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-reactor</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

## Use

### Spring Boot

Hook installed at app boot. Disable with `allstak.capture-reactor=false`.

### Plain

```java
AllStakReactorHooks.register();   // somewhere early at startup
// ...
AllStakReactorHooks.unregister(); // optional, idempotent
```

## Why

Without the hook, a `Mono.subscribeOn(Schedulers.parallel())`
chain runs the operator on a worker thread that has its own
isolation scope — captures inside the chain see nothing of what the
calling request handler set. The hook copies the snapshot into the
Reactor `Context`, then re-applies it on each `onNext`/`onError`.
