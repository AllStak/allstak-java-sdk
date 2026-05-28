# allstak-kotlin-coroutines

Java helpers usable from Kotlin coroutines (or any thread-handoff
pattern) to propagate the active AllStak isolation scope.

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-kotlin-coroutines</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

## Use

```kotlin
suspend fun handleOrder() {
    val snap = ScopeSnapshot.capture()
    launch(Dispatchers.IO) {
        snap.restore()
        try { processOrder() } finally { snap.clear() }
    }
}
```

For a full `CoroutineContext.Element` wrapper, ship a thin Kotlin
adapter in your application — the Java helper stays minimal so the
SDK build doesn't add the Kotlin compiler.
