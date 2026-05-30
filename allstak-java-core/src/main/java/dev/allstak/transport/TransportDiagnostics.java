package dev.allstak.transport;

/** Counter-only transport diagnostics. Contains no telemetry payload data. */
public final class TransportDiagnostics {

    private final long eventsCaptured;
    private final long eventsSent;
    private final long eventsFailed;
    private final long eventsDropped;
    private final long retryAttempts;
    private final long rateLimitedCount;
    private final long compressedPayloads;
    private final long uncompressedPayloads;
    private final long compressionBytesSaved;
    private final boolean disabled;

    TransportDiagnostics(
            long eventsCaptured,
            long eventsSent,
            long eventsFailed,
            long eventsDropped,
            long retryAttempts,
            long rateLimitedCount,
            long compressedPayloads,
            long uncompressedPayloads,
            long compressionBytesSaved,
            boolean disabled) {
        this.eventsCaptured = eventsCaptured;
        this.eventsSent = eventsSent;
        this.eventsFailed = eventsFailed;
        this.eventsDropped = eventsDropped;
        this.retryAttempts = retryAttempts;
        this.rateLimitedCount = rateLimitedCount;
        this.compressedPayloads = compressedPayloads;
        this.uncompressedPayloads = uncompressedPayloads;
        this.compressionBytesSaved = compressionBytesSaved;
        this.disabled = disabled;
    }

    public long getEventsCaptured() { return eventsCaptured; }
    public long getEventsSent() { return eventsSent; }
    public long getEventsFailed() { return eventsFailed; }
    public long getEventsDropped() { return eventsDropped; }
    public long getRetryAttempts() { return retryAttempts; }
    public long getRateLimitedCount() { return rateLimitedCount; }
    public long getCompressedPayloads() { return compressedPayloads; }
    public long getUncompressedPayloads() { return uncompressedPayloads; }
    public long getCompressionBytesSaved() { return compressionBytesSaved; }
    public boolean isDisabled() { return disabled; }
}
