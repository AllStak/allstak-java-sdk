package dev.allstak.scope;

import dev.allstak.model.Breadcrumb;
import dev.allstak.model.UserContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Three-layer scope stack: <b>Global</b>, <b>Isolation</b>, <b>Current</b>.
 *
 * <ul>
 *   <li><b>Global</b> — a single process-wide scope. Use for app-wide tags
 *       like {@code service.name} or {@code deployment.region}.</li>
 *   <li><b>Isolation</b> — per logical "unit of work" (HTTP request,
 *       background job). The Spring servlet filter and the @Scheduled
 *       wrapper push a fresh isolation scope per request/job so context
 *       does not leak between concurrent units.</li>
 *   <li><b>Current</b> — a temporary innermost scope pushed by
 *       {@link #withScope(Consumer)} that pops on return. Use to attach
 *       one-shot context to a single capture call.</li>
 * </ul>
 *
 * <p>{@link #mergedForCapture()} returns the {@link MergedScope} a capture
 * call should read from. Precedence: <b>Current &gt; Isolation &gt; Global</b>
 * for scalars; for maps and breadcrumb lists, all three layers are merged
 * with the more specific layer overwriting / appending.
 */
public final class Scopes {

    private static final Scope GLOBAL = new Scope();

    /**
     * Isolation scope is per-thread but inheritable so child threads spawned
     * inside a request handler observe the same scope unless they explicitly
     * push their own.
     */
    private static final InheritableThreadLocal<Scope> ISOLATION = new InheritableThreadLocal<>() {
        @Override
        protected Scope initialValue() { return new Scope(); }

        @Override
        protected Scope childValue(Scope parent) { return parent == null ? new Scope() : parent.copy(); }
    };

    /**
     * Stack of Current scopes. The active Current scope is the top of the
     * stack; an empty stack means "no current scope" — the isolation scope
     * is treated as the current view.
     */
    private static final ThreadLocal<java.util.Deque<Scope>> CURRENT_STACK =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);

    private Scopes() {}

    // ─── Access ────────────────────────────────────────────────────────────

    public static Scope global() { return GLOBAL; }

    public static Scope isolation() { return ISOLATION.get(); }

    /** Top Current scope, or the isolation scope if no Current is pushed. */
    public static Scope current() {
        java.util.Deque<Scope> stack = CURRENT_STACK.get();
        Scope top = stack.peek();
        return top != null ? top : ISOLATION.get();
    }

    // ─── Block helpers ─────────────────────────────────────────────────────

    /**
     * Pushes a fresh isolation scope, runs {@code block}, then restores the
     * previous one. Use to wrap a unit of work (request, job) so its context
     * doesn't leak.
     */
    public static void withIsolationScope(Consumer<Scope> block) {
        Scope previous = ISOLATION.get();
        Scope fresh = new Scope();
        ISOLATION.set(fresh);
        try {
            block.accept(fresh);
        } finally {
            if (previous == null) ISOLATION.remove();
            else ISOLATION.set(previous);
        }
    }

    /**
     * Forks the current isolation scope into a temporary Current scope, runs
     * {@code block}, then pops. Mutations inside {@code block} do not affect
     * the isolation scope.
     */
    public static void withScope(Consumer<Scope> block) {
        // Fresh empty Current pushed on top — the merged view at capture is
        // Global + Isolation + this Current, so users get inheritance via
        // the merge, not via copying. Avoids double-counting breadcrumbs.
        java.util.Deque<Scope> stack = CURRENT_STACK.get();
        Scope fork = new Scope();
        stack.push(fork);
        try {
            block.accept(fork);
        } finally {
            stack.pop();
            if (stack.isEmpty()) CURRENT_STACK.remove();
        }
    }

    /**
     * Run {@code block} against the currently active scope (Current if
     * present, else Isolation). Useful to read or accumulate context
     * without forking.
     */
    public static void configureScope(Consumer<Scope> block) {
        block.accept(current());
    }

    /** Resets every layer — used by tests and {@code AllStak.reset()}. */
    public static void clear() {
        GLOBAL.clear();
        ISOLATION.get().clear();
        ISOLATION.remove();
        java.util.Deque<Scope> stack = CURRENT_STACK.get();
        stack.clear();
        CURRENT_STACK.remove();
    }

    // ─── Merge ─────────────────────────────────────────────────────────────

    /**
     * Snapshot the three layers into one. Producers should call this once
     * per capture and read everything from the returned object.
     */
    public static MergedScope mergedForCapture() {
        Scope g = GLOBAL;
        Scope iso = ISOLATION.get();
        Scope cur = currentOrNull();

        UserContext user = pickUser(g.getUser(), iso.getUser(), cur != null ? cur.getUser() : null);
        String level = pickScalar(g.getLevel(), iso.getLevel(), cur != null ? cur.getLevel() : null);
        String transaction = pickScalar(g.getTransaction(), iso.getTransaction(), cur != null ? cur.getTransaction() : null);
        List<String> fingerprint = pickList(g.getFingerprint(), iso.getFingerprint(), cur != null ? cur.getFingerprint() : null);

        Map<String, String> tags = new LinkedHashMap<>();
        tags.putAll(g.getTags());
        tags.putAll(iso.getTags());
        if (cur != null) tags.putAll(cur.getTags());

        Map<String, Object> contexts = new LinkedHashMap<>();
        contexts.putAll(g.getContexts());
        contexts.putAll(iso.getContexts());
        if (cur != null) contexts.putAll(cur.getContexts());

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.putAll(g.getExtras());
        extras.putAll(iso.getExtras());
        if (cur != null) extras.putAll(cur.getExtras());

        // Breadcrumbs: concatenated Global → Isolation → Current. Since
        // withScope pushes a *fresh* Current (no copy), there's no
        // double-counting. Inheritance is realised through the merge,
        // not through copy.
        List<Breadcrumb> breadcrumbs = new ArrayList<>(
                g.getBreadcrumbs().size() + iso.getBreadcrumbs().size()
                        + (cur != null ? cur.getBreadcrumbs().size() : 0));
        breadcrumbs.addAll(g.getBreadcrumbs());
        breadcrumbs.addAll(iso.getBreadcrumbs());
        if (cur != null) breadcrumbs.addAll(cur.getBreadcrumbs());

        return new MergedScope(user, level, transaction, fingerprint,
                Collections.unmodifiableMap(tags),
                Collections.unmodifiableMap(contexts),
                Collections.unmodifiableMap(extras),
                Collections.unmodifiableList(breadcrumbs));
    }

    private static Scope currentOrNull() {
        java.util.Deque<Scope> stack = CURRENT_STACK.get();
        return stack.peek();
    }

    private static <T> T pickScalar(T global, T isolation, T current) {
        if (current != null) return current;
        if (isolation != null) return isolation;
        return global;
    }

    private static UserContext pickUser(UserContext g, UserContext iso, UserContext cur) {
        if (cur != null) return cur;
        if (iso != null) return iso;
        return g;
    }

    private static <T> List<T> pickList(List<T> g, List<T> iso, List<T> cur) {
        if (cur != null && !cur.isEmpty()) return cur;
        if (iso != null && !iso.isEmpty()) return iso;
        return g;
    }
}
