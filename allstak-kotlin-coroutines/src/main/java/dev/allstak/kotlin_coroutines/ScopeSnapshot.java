package dev.allstak.kotlin_coroutines;

import dev.allstak.scope.Scope;
import dev.allstak.scope.Scopes;

/**
 * Snapshot-and-restore helper for Kotlin coroutine consumers (or any
 * thread-handoff pattern). Kotlin sample:
 *
 * <pre>{@code
 * suspend fun handleOrder() {
 *     val snapshot = ScopeSnapshot.capture()
 *     launch(Dispatchers.IO) {
 *         snapshot.restore()
 *         try { … } finally { snapshot.clear() }
 *     }
 * }
 * }</pre>
 *
 * <p>For a full {@code CoroutineContext.Element} integration, ship a
 * thin Kotlin wrapper in your application. Keeping the helper in Java
 * avoids adding the Kotlin compiler to the SDK build for now.
 */
public final class ScopeSnapshot {

    private final Scope snapshot;

    private ScopeSnapshot(Scope snapshot) { this.snapshot = snapshot; }

    /** Capture the calling thread's isolation scope into an immutable snapshot. */
    public static ScopeSnapshot capture() {
        return new ScopeSnapshot(Scopes.isolation().copy());
    }

    /** Overlay this snapshot onto the current thread's isolation scope. */
    public void restore() {
        Scope target = Scopes.isolation();
        if (snapshot.getUser() != null) target.setUser(snapshot.getUser());
        snapshot.getTags().forEach(target::setTag);
        snapshot.getContexts().forEach(target::setContext);
        snapshot.getExtras().forEach(target::setExtra);
        snapshot.getBreadcrumbs().forEach(target::addBreadcrumb);
    }

    /** Clear the thread's isolation scope — call when the worker is done. */
    public void clear() { Scopes.isolation().clear(); }

    Scope snapshot() { return snapshot; }
}
