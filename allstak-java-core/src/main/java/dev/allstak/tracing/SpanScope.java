package dev.allstak.tracing;

/**
 * Thread-local registry of the currently active {@link Span} so that
 * auto-instrumentation (HTTP/DB/cache interceptors) and {@code captureException}
 * can discover the in-flight transaction/span and attach to it — the
 * "current span" the same way Sentry exposes {@code getSpan()} on the hub.
 *
 * <p>The active span is maintained as a per-thread stack: {@link #push(Span)}
 * when a span starts, {@link #pop(Span)} when it finishes. {@link #current()}
 * returns the innermost active span (or {@code null} when no transaction is in
 * flight). The thread-local is inheritable so spans created on child threads
 * spawned inside a transaction observe the same parent unless they push their
 * own.
 *
 * <p>All operations are fail-open and never throw; instrumentation must be able
 * to call {@link #current()} on any thread without guarding.
 */
public final class SpanScope {

    private static final InheritableThreadLocal<java.util.Deque<Span>> STACK =
            new InheritableThreadLocal<>() {
                @Override
                protected java.util.Deque<Span> initialValue() {
                    return new java.util.ArrayDeque<>();
                }

                @Override
                protected java.util.Deque<Span> childValue(java.util.Deque<Span> parent) {
                    // Child threads observe the parent's active span as their
                    // current span, but get an independent stack so their own
                    // push/pop doesn't mutate the parent's.
                    java.util.Deque<Span> copy = new java.util.ArrayDeque<>();
                    if (parent != null) {
                        Span top = parent.peek();
                        if (top != null) copy.push(top);
                    }
                    return copy;
                }
            };

    private SpanScope() {}

    /** The innermost active span on this thread, or {@code null}. */
    /*@Nullable*/ public static Span current() {
        try {
            return STACK.get().peek();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Push {@code span} as the new current span for this thread. No-op on null. */
    public static void push(Span span) {
        if (span == null) return;
        try {
            STACK.get().push(span);
        } catch (Throwable ignored) {
            // Fail-open: a broken thread-local must never break tracing.
        }
    }

    /**
     * Remove {@code span} from this thread's active stack. Removes the matching
     * instance wherever it sits (tolerant of out-of-order finishes) and clears
     * the thread-local once the stack drains so threads from a pool don't leak
     * span references. No-op on null.
     */
    public static void pop(Span span) {
        if (span == null) return;
        try {
            java.util.Deque<Span> stack = STACK.get();
            stack.removeFirstOccurrence(span);
            if (stack.isEmpty()) STACK.remove();
        } catch (Throwable ignored) {
            // Fail-open.
        }
    }

    /** Clear the active-span stack for this thread — used by tests / reset. */
    public static void clear() {
        try {
            STACK.get().clear();
            STACK.remove();
        } catch (Throwable ignored) {
            // Fail-open.
        }
    }
}
