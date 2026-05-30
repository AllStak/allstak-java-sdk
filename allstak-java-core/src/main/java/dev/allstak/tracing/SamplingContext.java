package dev.allstak.tracing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-transaction sampling input passed to a {@link TracesSampler}. The
 * sampler returns a probability in [0.0, 1.0] (or {@code null} to fall back
 * to {@code tracesSampleRate}) for each transaction-start decision so callers
 * can weight high-value routes (checkout, signup) heavier than chatty ones
 * (health, polling).
 *
 * <p>Carries request-shape data plus the parent's sampled bit propagated
 * through the incoming W3C {@code traceparent} header.
 */
public final class SamplingContext {

    private final String operation;
    private final String name;
    private final String method;
    private final String url;
    private final Boolean parentSampled;
    private final Map<String, Object> attributes;

    public SamplingContext(/*@Nullable*/ String operation,
                           /*@Nullable*/ String name,
                           /*@Nullable*/ String method,
                           /*@Nullable*/ String url,
                           /*@Nullable*/ Boolean parentSampled,
                           /*@Nullable*/ Map<String, Object> attributes) {
        this.operation = operation;
        this.name = name;
        this.method = method;
        this.url = url;
        this.parentSampled = parentSampled;
        this.attributes = attributes == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /** e.g. {@code "http.server"}, {@code "scheduled"}, {@code "kafka.consume"}. */
    /*@Nullable*/ public String operation() { return operation; }

    /** Human-readable transaction name — usually the inbound route ({@code POST /api/orders}). */
    /*@Nullable*/ public String name() { return name; }

    /*@Nullable*/ public String method() { return method; }
    /*@Nullable*/ public String url() { return url; }

    /** Parent's sampled bit if a remote {@code traceparent} arrived, else null. */
    public Boolean parentSampled() { return parentSampled; }

    /** Free-form per-call attributes (Spring matched-pattern, user role, etc.). */
    public Map<String, Object> attributes() { return attributes; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String operation;
        private String name;
        private String method;
        private String url;
        private Boolean parentSampled;
        private Map<String, Object> attributes;

        public Builder operation(String v) { this.operation = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder method(String v) { this.method = v; return this; }
        public Builder url(String v) { this.url = v; return this; }
        public Builder parentSampled(Boolean v) { this.parentSampled = v; return this; }
        public Builder attributes(Map<String, Object> v) { this.attributes = v; return this; }

        public SamplingContext build() {
            return new SamplingContext(operation, name, method, url, parentSampled, attributes);
        }
    }
}
