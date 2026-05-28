package dev.allstak.transport;

/**
 * Outcome of a transport send, richer than the legacy {@code boolean}. Lets the
 * offline spool decide whether a failed envelope should be PERSISTED for a
 * later retry (transient: network down, retries exhausted, server 5xx/429) or
 * DROPPED (permanent: 4xx other than 429, auth-disabled SDK).
 */
public enum SendResult {

    /** Accepted by the backend (2xx). Remove from the spool. */
    ACCEPTED,

    /**
     * Could not be delivered now but might succeed later — network error,
     * timeout, retries exhausted, or a retryable server status. Persist /
     * keep on the spool for the next drain.
     */
    TRANSIENT,

    /**
     * Will never be accepted as-is — a non-retryable client error (4xx other
     * than 429) or the SDK is disabled after a 401. Do not persist; drop any
     * spooled copy so it cannot wedge the drain.
     */
    PERMANENT;

    /** Back-compat: the legacy boolean send contract returns true only for 2xx. */
    public boolean isAccepted() {
        return this == ACCEPTED;
    }
}
