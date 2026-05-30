package dev.allstak.scope;

import dev.allstak.model.Breadcrumb;
import dev.allstak.model.UserContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A single layer of context held by the {@link Scopes} stack. A Scope owns:
 *
 * <ul>
 *   <li>a user</li>
 *   <li>tags (low-cardinality, filterable on the dashboard)</li>
 *   <li>contexts (structured blobs)</li>
 *   <li>extras (free-form key/value)</li>
 *   <li>a bounded breadcrumb buffer</li>
 *   <li>level / transaction / fingerprint overrides</li>
 * </ul>
 *
 * <p>Scopes are layered: a captured event sees the merged view of Global +
 * Isolation + Current with the more specific layer winning for scalar fields
 * (user, level, transaction, fingerprint) and the union for list/map fields
 * (tags, contexts, extras, breadcrumbs). The merge logic lives in
 * {@link Scopes#mergedForCapture()}.
 *
 * <p>All mutation is read/write-lock guarded so background threads can safely
 * read while a request handler updates the isolation scope.
 *
 * <p>Implements a three-layer scopes API adapted to the AllStak model surface.
 */
public final class Scope {

    /** Maximum number of breadcrumbs retained per scope before older ones are dropped. */
    public static final int DEFAULT_MAX_BREADCRUMBS = 100;

    private final int maxBreadcrumbs;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private UserContext user;
    private String level;
    private String transaction;
    private List<String> fingerprint;

    private final Map<String, String> tags = new LinkedHashMap<>();
    private final Map<String, Object> contexts = new LinkedHashMap<>();
    private final Map<String, Object> extras = new LinkedHashMap<>();
    private final List<Breadcrumb> breadcrumbs = new ArrayList<>();

    public Scope() {
        this(DEFAULT_MAX_BREADCRUMBS);
    }

    public Scope(int maxBreadcrumbs) {
        this.maxBreadcrumbs = Math.max(1, maxBreadcrumbs);
    }

    // ─── User ──────────────────────────────────────────────────────────────

    public void setUser(UserContext user) {
        lock.writeLock().lock();
        try { this.user = user; } finally { lock.writeLock().unlock(); }
    }

    public UserContext getUser() {
        lock.readLock().lock();
        try { return user; } finally { lock.readLock().unlock(); }
    }

    // ─── Tags / contexts / extras ──────────────────────────────────────────

    public void setTag(String key, String value) {
        if (key == null) return;
        lock.writeLock().lock();
        try { tags.put(key, value); } finally { lock.writeLock().unlock(); }
    }

    public void removeTag(String key) {
        if (key == null) return;
        lock.writeLock().lock();
        try { tags.remove(key); } finally { lock.writeLock().unlock(); }
    }

    public Map<String, String> getTags() {
        lock.readLock().lock();
        try { return Collections.unmodifiableMap(new LinkedHashMap<>(tags)); }
        finally { lock.readLock().unlock(); }
    }

    public void setContext(String key, Object value) {
        if (key == null) return;
        lock.writeLock().lock();
        try { contexts.put(key, value); } finally { lock.writeLock().unlock(); }
    }

    public void removeContext(String key) {
        if (key == null) return;
        lock.writeLock().lock();
        try { contexts.remove(key); } finally { lock.writeLock().unlock(); }
    }

    public Map<String, Object> getContexts() {
        lock.readLock().lock();
        try { return Collections.unmodifiableMap(new LinkedHashMap<>(contexts)); }
        finally { lock.readLock().unlock(); }
    }

    public void setExtra(String key, Object value) {
        if (key == null) return;
        lock.writeLock().lock();
        try { extras.put(key, value); } finally { lock.writeLock().unlock(); }
    }

    public void removeExtra(String key) {
        if (key == null) return;
        lock.writeLock().lock();
        try { extras.remove(key); } finally { lock.writeLock().unlock(); }
    }

    public Map<String, Object> getExtras() {
        lock.readLock().lock();
        try { return Collections.unmodifiableMap(new LinkedHashMap<>(extras)); }
        finally { lock.readLock().unlock(); }
    }

    // ─── Breadcrumbs ───────────────────────────────────────────────────────

    public void addBreadcrumb(Breadcrumb crumb) {
        if (crumb == null) return;
        lock.writeLock().lock();
        try {
            breadcrumbs.add(crumb);
            while (breadcrumbs.size() > maxBreadcrumbs) {
                breadcrumbs.remove(0);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clearBreadcrumbs() {
        lock.writeLock().lock();
        try { breadcrumbs.clear(); } finally { lock.writeLock().unlock(); }
    }

    public List<Breadcrumb> getBreadcrumbs() {
        lock.readLock().lock();
        try { return new ArrayList<>(breadcrumbs); } finally { lock.readLock().unlock(); }
    }

    // ─── Scalar overrides ──────────────────────────────────────────────────

    public void setLevel(String level) {
        lock.writeLock().lock();
        try { this.level = level; } finally { lock.writeLock().unlock(); }
    }

    public String getLevel() {
        lock.readLock().lock();
        try { return level; } finally { lock.readLock().unlock(); }
    }

    public void setTransaction(String transaction) {
        lock.writeLock().lock();
        try { this.transaction = transaction; } finally { lock.writeLock().unlock(); }
    }

    public String getTransaction() {
        lock.readLock().lock();
        try { return transaction; } finally { lock.readLock().unlock(); }
    }

    public void setFingerprint(List<String> fingerprint) {
        lock.writeLock().lock();
        try {
            this.fingerprint = fingerprint == null ? null : new ArrayList<>(fingerprint);
        } finally { lock.writeLock().unlock(); }
    }

    public List<String> getFingerprint() {
        lock.readLock().lock();
        try { return fingerprint == null ? null : new ArrayList<>(fingerprint); }
        finally { lock.readLock().unlock(); }
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────

    /** Resets every field — used by {@code Scopes.clear()}. */
    public void clear() {
        lock.writeLock().lock();
        try {
            user = null;
            level = null;
            transaction = null;
            fingerprint = null;
            tags.clear();
            contexts.clear();
            extras.clear();
            breadcrumbs.clear();
        } finally { lock.writeLock().unlock(); }
    }

    /** Deep copy — used to fork a scope (e.g. on {@code withScope}). */
    public Scope copy() {
        lock.readLock().lock();
        try {
            Scope c = new Scope(maxBreadcrumbs);
            c.user = this.user;
            c.level = this.level;
            c.transaction = this.transaction;
            c.fingerprint = this.fingerprint == null ? null : new ArrayList<>(this.fingerprint);
            c.tags.putAll(this.tags);
            c.contexts.putAll(this.contexts);
            c.extras.putAll(this.extras);
            c.breadcrumbs.addAll(this.breadcrumbs);
            return c;
        } finally { lock.readLock().unlock(); }
    }
}
