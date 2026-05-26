package dev.allstak.spring;

/**
 * Functional bean type for the AllStak {@code beforeSend} hook in Spring apps.
 *
 * <p>Define a single {@code @Bean} of this type to intercept every event just
 * before it is sent to the AllStak ingest API. The event is the SDK's event
 * type ({@link dev.allstak.model.ErrorEvent} or {@link dev.allstak.model.LogEvent}).
 * Return the event (modified or not) to keep it, or {@code null} to drop it.
 *
 * <p>Runs after sample-rate filtering and before PII masking. If the callback
 * throws, the SDK fails open and sends the original event.
 *
 * <pre>{@code
 * @Bean
 * AllStakBeforeSend dropHealthChecks() {
 *     return event -> {
 *         if (event instanceof ErrorEvent e && "HealthCheckException".equals(e.getExceptionClass())) {
 *             return null; // drop
 *         }
 *         return event;
 *     };
 * }
 * }</pre>
 */
@FunctionalInterface
public interface AllStakBeforeSend {

    /**
     * @param event the SDK event ({@code ErrorEvent} / {@code LogEvent}).
     * @return the (possibly modified) event, or {@code null} to drop it.
     */
    Object beforeSend(Object event);
}
