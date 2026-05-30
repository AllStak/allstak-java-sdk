package dev.allstak;

/**
 * Privacy-safe SDK diagnostics. Contains counters and queue sizes only; it
 * never includes telemetry payloads, headers, tags, context values, or user data.
 */
public final class AllStakDiagnostics {

    private final long eventsCaptured;
    private final long eventsSent;
    private final long eventsFailed;
    private final long eventsDropped;
    private final long eventsPersisted;
    private final long eventsReplayed;
    private final int queueSize;
    private final long retryAttempts;
    private final long rateLimitedCount;
    private final long compressedPayloads;
    private final long uncompressedPayloads;
    private final long compressionBytesSaved;
    private final long sanitizerRedactionCount;
    private final int activeTraceCount;
    private final int activeSpanCount;
    private final int breadcrumbCount;
    private final long sessionRecoveryCount;
    private final boolean disabled;

    public AllStakDiagnostics(
            long eventsCaptured,
            long eventsSent,
            long eventsFailed,
            long eventsDropped,
            long eventsPersisted,
            long eventsReplayed,
            int queueSize,
            long retryAttempts,
            long rateLimitedCount,
            long compressedPayloads,
            long uncompressedPayloads,
            long compressionBytesSaved,
            long sanitizerRedactionCount,
            int activeTraceCount,
            int activeSpanCount,
            int breadcrumbCount,
            long sessionRecoveryCount,
            boolean disabled) {
        this.eventsCaptured = eventsCaptured;
        this.eventsSent = eventsSent;
        this.eventsFailed = eventsFailed;
        this.eventsDropped = eventsDropped;
        this.eventsPersisted = eventsPersisted;
        this.eventsReplayed = eventsReplayed;
        this.queueSize = queueSize;
        this.retryAttempts = retryAttempts;
        this.rateLimitedCount = rateLimitedCount;
        this.compressedPayloads = compressedPayloads;
        this.uncompressedPayloads = uncompressedPayloads;
        this.compressionBytesSaved = compressionBytesSaved;
        this.sanitizerRedactionCount = sanitizerRedactionCount;
        this.activeTraceCount = activeTraceCount;
        this.activeSpanCount = activeSpanCount;
        this.breadcrumbCount = breadcrumbCount;
        this.sessionRecoveryCount = sessionRecoveryCount;
        this.disabled = disabled;
    }

    public long getEventsCaptured() { return eventsCaptured; }
    public long getEventsSent() { return eventsSent; }
    public long getEventsFailed() { return eventsFailed; }
    public long getEventsDropped() { return eventsDropped; }
    public long getEventsPersisted() { return eventsPersisted; }
    public long getEventsReplayed() { return eventsReplayed; }
    public int getQueueSize() { return queueSize; }
    public long getRetryAttempts() { return retryAttempts; }
    public long getRateLimitedCount() { return rateLimitedCount; }
    public long getCompressedPayloads() { return compressedPayloads; }
    public long getUncompressedPayloads() { return uncompressedPayloads; }
    public long getCompressionBytesSaved() { return compressionBytesSaved; }
    public long getSanitizerRedactionCount() { return sanitizerRedactionCount; }
    public int getActiveTraceCount() { return activeTraceCount; }
    public int getActiveSpanCount() { return activeSpanCount; }
    public int getBreadcrumbCount() { return breadcrumbCount; }
    public long getSessionRecoveryCount() { return sessionRecoveryCount; }
    public boolean isDisabled() { return disabled; }
}
