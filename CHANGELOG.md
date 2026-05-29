# Changelog

All notable changes to the AllStak Java SDK live here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added — release-health session tracking

- **Crash-free session tracking** — `SessionTracker` now records
  session start/end with errored/crashed/exited transitions and feeds
  the crash-free rate. Sessions post to `/ingest/v1/sessions/start` and
  `/sessions/end`. Wired through `AllStakClient` lifecycle (init →
  shutdown).

### Added — offline / persistent transport queue

- **Disk-backed event spool** — `dev.allstak.spool.EventSpool` /
  `SpoolEntry` persist undelivered events to disk so they survive a JVM
  restart or a backend outage and replay when transport recovers.
- `HttpTransport` now reports a structured `SendResult` (success /
  retryable / dropped) so the client can decide whether to spool, retry,
  or discard. New `AllStakConfig` knobs control the offline queue
  (enablement, spool directory, capacity).

### Added — value-pattern PII scrubbing

- **Value-based data scrubbing** layered on top of the existing
  key-name redaction in `DataMasker`, gated by `sendDefaultPii`
  (default `false` = Sentry parity):
  - Always scrubbed: credit-card numbers (13–19 digits, Luhn + card-IIN
    double gate to avoid false positives on order ids / timestamps) and
    hyphenated US SSNs.
  - Scrubbed unless `sendDefaultPii=true`: email addresses and
    octet-validated IPv4 literals.
- Applied to string values on the wire path only (error/log messages,
  metadata/extra/contexts recursively, breadcrumb message + data,
  captured HTTP bodies). Explicit `setUser` fields, stack-frame
  metadata, release/sdk fields, URLs, span names, and the session id are
  not scrubbed. Fail-open throughout; regexes compiled once with capped
  scan length and recursion depth.

### Added — first-class transaction / span API

- **Manual tracing API** (sentry-java parity): `AllStak.startTransaction(name, op)`
  returns a `Transaction` (root span); `startChild(op, description)` nests
  spans sharing the trace id with correct parent-span linkage.
- `Span` supports `setTag` / `setData` / `setStatus` / `setDescription` /
  `finish`; new `SpanStatus` enum with Sentry-style values and
  `fromHttpStatus()` mapping.
- Sampling is decided once at transaction start (reusing
  `tracesSampler` / `tracesSampleRate` / parent-sampled bit) and
  inherited by every child. Finished sampled spans emit to
  `/ingest/v1/spans` with durations and release/env/session context.
- `SpanScope` thread-local exposes the active span
  (`AllStak.getCurrentSpan`); the `allstak-okhttp` interceptor now nests
  under the in-flight transaction when one exists, falling back to a
  fresh root trace otherwise.

### Housekeeping

- Stopped tracking Maven `target/` build output that had been committed
  since the initial commit (`*.class`, surefire reports, compiler-status
  lists, copied resources) — these were already listed in `.gitignore`
  but remained in the index. Expanded `.gitignore` (jars/wars, IDE
  metadata, `.DS_Store`). No source files touched.

## [0.2.1] — 2026-05-29

### Changed — log appenders auto-promote ERROR with throwable to Issues

`AllStakLogbackAppender` and `AllStakLog4j2Appender` now call
`captureException` (in addition to `captureLog`) when an event is at
`ERROR` level and carries a real `Throwable`. Caught-and-logged
exceptions — e.g. `log.error("op failed", ex)` inside a `try/catch`
that doesn't rethrow — now surface in the AllStak Issues view, not
only in Logs. This matches Sentry's appender behavior.

- WARN-with-throwable still stays Log-only (anomalies, not issues).
- Plain `log.error("msg only")` with no Throwable still stays Log-only.
- The throwable is only promoted when the appender can recover the
  real `Throwable` (Logback `ThrowableProxy`, Log4j2 `LogEvent.getThrown()`).
  Cross-process/serialized throwable proxies fall back to log-only.

## [0.2.0] — 2026-05-28

### Added — Phase A foundation (Sentry parity)

- **Scope / Hub model** — three-layer scope stack
  (`Scopes.global()`, `Scopes.isolation()`, `Scopes.current()`) with
  `withScope`, `withIsolationScope`, `configureScope`. Per-thread
  isolation via `InheritableThreadLocal`. AllStak.setUser /
  setTag / setExtra / setContext now write to the active scope;
  breadcrumbs are per-scope too.
- **Sessions / release health** — `SessionTracker` opens one session
  per JVM at init, closes it at shutdown. Errored/crashed transitions
  flow into the session status. Posts to
  `/ingest/v1/sessions/start` + `/sessions/end`.
- **PII defaults** — `sendDefaultPii=false` is now the default. The SDK
  strips `user.email` and `user.ip` from every event; the Spring
  servlet filter skips request/response body capture entirely. Opt in
  via `allstak.send-default-pii=true`.
- **`tracesSampler` + `tracePropagationTargets`** — callback-based
  per-transaction sampling with parent-sampled fallback; outbound
  trace-header allowlist applied across every HTTP integration.
- **`allstak-bom`** — new BOM artifact pinning every SDK module so
  consumers can drop one `dependencyManagement` import.
- **Manual release pipeline** — `scripts/release.sh` (no CI/CD).
  Pre-flights GPG + Sonatype Central + clean tree, bumps versions,
  builds + signs, uploads with `autoPublish=true`, tags, pushes,
  bumps to next `-SNAPSHOT`. See `docs/RELEASE.md`.

### Added — Phase B (outbound HTTP, async, scheduling, security)

- `allstak-okhttp` — OkHttp Interceptor (span + breadcrumb + trace
  headers, gated by `tracePropagationTargets`).
- `allstak-apache-http-client-5` — Apache HttpClient 5 request +
  response interceptors.
- `allstak-openfeign` — Feign RequestInterceptor.
- `allstak-reactor` — Project Reactor `onEachOperator` hook
  propagating the isolation scope across thread hops.
- `allstak-quartz` — Quartz `JobListener` emitting cron check-ins.
- `allstak-kafka` — Producer + Consumer interceptors with trace-header
  propagation.
- `allstak-spring-security` — pulls the authenticated principal into
  the active scope's user.

### Added — Phase C (data + GraphQL)

- `allstak-lettuce` — Lettuce Tracing SPI adapter.
- `allstak-jedis` — Manual command wrapper.
- `allstak-mongodb` — `CommandListener` emitting one span per Mongo
  command.
- `allstak-r2dbc` — `r2dbc-proxy` listener.
- `allstak-graphql` — `SimplePerformantInstrumentation` extension.
- `allstak-spring-cache` — Cache decorator wrapping get/put/evict.

### Added — Phase D (source context + Maven plugin)

- `dev.allstak.debug.DebugIdResolver` — reads
  `allstak-debug-meta.properties` from the JAR at runtime.
- `allstak-maven-plugin` — goals
  - `generate-debug-id` (writes the properties file into the JAR)
  - `upload-source-bundle` (zips every `.java` source and POSTs it to
    the platform).

### Added — Phase E (backpressure + profiling)

- `dev.allstak.backpressure.BackpressureController` — multiplicative-
  decrease / additive-increase factor applied on the static traces
  sample rate when transport reports 429/503 or queue overflow.
- `allstak-profiling-jfr` — continuous JFR profiler (every JDK 11+,
  no native binaries). Chunks every 30s to `/ingest/v1/profiles`.
  `allstak.enable-profiling=true` to opt in.

### Added — Phase F (misc surface)

- `AllStak.captureFeedback` + `captureAttachment` and the matching
  `UserFeedback` / `Attachment` models; endpoints
  `/ingest/v1/feedback` and `/ingest/v1/attachments`.
- `allstak-kotlin-coroutines` — `ScopeSnapshot` capture/restore helper
  ready to wrap as a Kotlin `CoroutineContext.Element`.
- `allstak-jms` — `jakarta.jms` header stamp / harvest.
- `allstak-rabbitmq` — AMQP `BasicProperties` header helper.
- `allstak-grpc` — `ClientInterceptor` + `ServerInterceptor`.
- `allstak-opentelemetry` — `SpanProcessor` that forwards finished
  OTel spans into AllStak's transport.

### Changed — Phase H (one-package UX)

- `allstak-spring-boot-starter` now transitively pulls every Phase B–F
  integration glue module at `compile` scope. A Spring Boot consumer
  adds **one** dependency and every instrumentation activates
  automatically based on what's already on their classpath
  (via `@ConditionalOnClass`).
- Total transitive footprint is ~190 KB of AllStak glue code; none of
  the third-party libraries (OkHttp, Kafka clients, Mongo driver,
  Lettuce, Jedis, Quartz, …) leak through, because each integration
  module declares its third-party dep as `provided`.
- Per-module artefact IDs remain published — power users who need
  pinpoint control can still depend on `allstak-okhttp` alone with
  `allstak-java-core`. The one-package path is the recommended one.

### Added — Phase G (Spring Boot auto-wiring)

- Each Phase B/C/E module is now auto-wired by
  `allstak-spring-boot-starter` when present on the classpath. Each
  integration has its own nested `@Configuration` gated by
  `@ConditionalOnClass`, and a corresponding
  `allstak.capture-<name>` flag in `AllStakProperties`.
- `application.properties` surface extended with:
  - `allstak.send-default-pii`
  - `allstak.enable-auto-session-tracking`
  - `allstak.trace-propagation-targets[]`
  - `allstak.enable-profiling`
  - `allstak.capture-{ok-http,apache-http,feign,reactor,quartz,kafka,security-user,lettuce,graphql,spring-cache}`

### Changed

- `AllStak.reset()` is now public (test seam used by every integration
  module's test suite).
- Servlet filter no longer captures request/response bodies by default
  — `sendDefaultPii=true` opts back in.

### Notes

- No release has been cut yet. Run `scripts/release.sh --dry-run 0.2.0`
  to validate the release profile + GPG signing without uploading.

## [0.1.5] - 2026-05-26

Last pre-Phase-A snapshot. Captured for context only; see git history
prior to this entry.
