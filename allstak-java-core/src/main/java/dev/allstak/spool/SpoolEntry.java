package dev.allstak.spool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One persisted, already-PII-scrubbed telemetry envelope awaiting (re)delivery.
 *
 * <p>The {@code path} records which ingest endpoint the payload belongs to so
 * the drainer can replay it through the existing transport without guessing.
 * The {@code payload} holds the masked JSON tree exactly as it was serialized
 * for the original (failed) send — no further scrubbing happens on replay
 * because scrubbing already ran before the entry was written. {@code createdAt}
 * is an epoch-milli stamp used to enforce the max-age bound.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SpoolEntry {

    private String path;
    private long createdAt;
    private JsonNode payload;

    // Jackson needs a no-arg ctor for deserialization.
    public SpoolEntry() {}

    public SpoolEntry(String path, long createdAt, JsonNode payload) {
        this.path = path;
        this.createdAt = createdAt;
        this.payload = payload;
    }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public JsonNode getPayload() { return payload; }
    public void setPayload(JsonNode payload) { this.payload = payload; }
}
