package dev.allstak.reactor;

import dev.allstak.scope.Scope;
import dev.allstak.scope.Scopes;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

/**
 * Project Reactor scope propagation. Without this hook, a Mono / Flux that
 * hops onto {@code Schedulers.parallel} loses the calling thread's
 * {@link Scopes#isolation()} state — captures inside the operator chain
 * see an empty scope.
 *
 * <p>{@link #register()} installs a Reactor {@code onEachOperator} hook
 * that copies the active isolation scope into the reactive {@link Context}
 * on subscribe and restores it on the worker thread before each onNext /
 * onError. {@link #unregister()} removes it; both are idempotent.
 *
 * <p>Spring Boot users get this wired automatically by the starter when
 * both modules are on the classpath.
 */
public final class AllStakReactorHooks {

    private static final String HOOK_KEY = "allstak.reactor.scope";
    private static final String CTX_KEY  = "allstak.scope";

    private AllStakReactorHooks() {}

    public static void register() {
        Hooks.onEachOperator(HOOK_KEY, Operators.lift((sc, sub) ->
                new ScopePropagatingSubscriber<>(sub)));
    }

    public static void unregister() {
        Hooks.resetOnEachOperator(HOOK_KEY);
    }

    /** Snapshot the current isolation scope into a Reactor Context entry. */
    public static Context captureContext(Context base) {
        Scope snapshot = Scopes.isolation().copy();
        return base.put(CTX_KEY, snapshot);
    }

    private static final class ScopePropagatingSubscriber<T> implements reactor.core.CoreSubscriber<T> {
        private final reactor.core.CoreSubscriber<? super T> downstream;
        private final Scope captured;

        ScopePropagatingSubscriber(reactor.core.CoreSubscriber<? super T> downstream) {
            this.downstream = downstream;
            this.captured = Scopes.isolation().copy();
        }

        @Override public Context currentContext() { return downstream.currentContext().put(CTX_KEY, captured); }
        @Override public void onSubscribe(org.reactivestreams.Subscription s) { downstream.onSubscribe(s); }
        @Override public void onNext(T t) { restoreAndRun(() -> downstream.onNext(t)); }
        @Override public void onError(Throwable t) { restoreAndRun(() -> downstream.onError(t)); }
        @Override public void onComplete() { restoreAndRun(downstream::onComplete); }

        private void restoreAndRun(Runnable r) {
            Scope previous = Scopes.isolation().copy();
            try {
                Scopes.isolation().clear();
                copyInto(captured, Scopes.isolation());
                r.run();
            } finally {
                Scopes.isolation().clear();
                copyInto(previous, Scopes.isolation());
            }
        }

        private static void copyInto(Scope src, Scope dst) {
            if (src.getUser() != null) dst.setUser(src.getUser());
            src.getTags().forEach(dst::setTag);
            src.getContexts().forEach(dst::setContext);
            src.getExtras().forEach(dst::setExtra);
            src.getBreadcrumbs().forEach(dst::addBreadcrumb);
        }
    }
}
