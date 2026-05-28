package dev.allstak.jms;

import dev.allstak.AllStak;
import dev.allstak.scope.Scopes;
import jakarta.jms.Message;

import java.util.UUID;

/**
 * Minimal JMS helper:
 *
 * <ul>
 *   <li>{@link #stamp(Message)} — set AllStak trace headers on an outbound message.</li>
 *   <li>{@link #harvest(Message)} — read inbound trace headers and copy them
 *       into the active isolation scope tags so any error captured during
 *       processing inherits the producer's trace.</li>
 * </ul>
 *
 * <p>JMS provider quirks (some only allow {@code _-}-free property names)
 * are why we use {@code allstak_trace_id} as the wire key.
 */
public final class AllStakJmsTrace {

    public static final String PROP_TRACE_ID = "allstak_trace_id";
    public static final String PROP_SPAN_ID  = "allstak_span_id";

    private AllStakJmsTrace() {}

    public static void stamp(Message message) {
        if (message == null || AllStak.getClient() == null) return;
        try {
            if (message.getStringProperty(PROP_TRACE_ID) == null) {
                message.setStringProperty(PROP_TRACE_ID, UUID.randomUUID().toString());
                message.setStringProperty(PROP_SPAN_ID,  UUID.randomUUID().toString().substring(0, 16));
            }
        } catch (Exception ignored) {}
    }

    public static void harvest(Message message) {
        if (message == null) return;
        try {
            String traceId = message.getStringProperty(PROP_TRACE_ID);
            if (traceId != null) Scopes.isolation().setTag("trace.id", traceId);
            String spanId = message.getStringProperty(PROP_SPAN_ID);
            if (spanId != null) Scopes.isolation().setTag("span.parent_id", spanId);
        } catch (Exception ignored) {}
    }
}
