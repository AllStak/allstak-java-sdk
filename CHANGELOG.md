# Changelog

## 0.1.4 — 2026-05-18

### Security — canonical denylist parity + transport wire scrub
- `DataMasker` rewrite: expanded denylist to canonical 25 terms used across the AllStak SDK ecosystem; switched matching from exact `Set.contains` to case-insensitive substring match; added recursive walking over nested Maps/Collections/arrays; added cycle protection via IdentityHashMap; pure no-mutation; new `maskWire(Object)` entry point for transport-level scrub. Sentinel renamed `[MASKED]` → `[REDACTED]` to match the ecosystem; legacy `MASKED` constant kept as deprecated alias.
- `HttpTransport.send` now scrubs the full wire payload (Jackson serialize → tree parse → recursive scrub → reserialize) before transmitting. One chokepoint protects every telemetry type. Fail-open.

### Live canary E2E
- Event `ad62368a-33d5-47b5-94af-f76e2a67de49` against `api.allstak.sa`. ClickHouse `leak_pos = 0` across all 4 columns. Canary `should_not_leak_java` planted in 11 fields + 3-level-nested `token` — all scrubbed.

### Tests
- 82/82 JUnit tests pass. Test literals migrated to `[REDACTED]`.

### Publish status
- **Not on Maven Central.** Ships on `AllStak/allstak-java-sdk` via git tag `v0.1.4` only. Owner action remaining: Sonatype OSSRH account for `sa.allstak` + GPG signing key.

## 0.1.3 - 2026-05-15

- Expanded Spring Boot auto-instrumentation for async, cache, Feign, Kafka, RabbitMQ, retry, security, and Redis flows.
- Added configurable ingest host and HTTP body-capture controls.
- Improved fail-open buffering for errors, heartbeats, spans, HTTP requests, logs, and DB telemetry.
- Replaced sample app API-key literals with environment placeholders.
- Added integration coverage and a Spring Boot demo example.
