package dev.allstak.tracing;

/**
 * Canonical span/transaction outcome statuses, modeled on Sentry's
 * {@code SpanStatus} (which in turn mirrors the OpenTelemetry status set).
 * The on-the-wire value is the lowercase {@link #wire()} string; the AllStak
 * spans ingest already understands {@code "ok"} / {@code "error"} and tolerates
 * the richer set as free-form status text.
 *
 * <p>Use {@link #fromHttpStatus(int)} to map an HTTP response code to a span
 * status the way Sentry does, so HTTP-client/-server spans report a meaningful
 * outcome without callers hand-rolling the mapping.
 */
public enum SpanStatus {

    OK("ok"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown"),
    INVALID_ARGUMENT("invalid_argument"),
    DEADLINE_EXCEEDED("deadline_exceeded"),
    NOT_FOUND("not_found"),
    ALREADY_EXISTS("already_exists"),
    PERMISSION_DENIED("permission_denied"),
    RESOURCE_EXHAUSTED("resource_exhausted"),
    FAILED_PRECONDITION("failed_precondition"),
    ABORTED("aborted"),
    OUT_OF_RANGE("out_of_range"),
    UNIMPLEMENTED("unimplemented"),
    INTERNAL_ERROR("internal_error"),
    UNAVAILABLE("unavailable"),
    DATA_LOSS("data_loss"),
    UNAUTHENTICATED("unauthenticated");

    private final String wire;

    SpanStatus(String wire) {
        this.wire = wire;
    }

    /** The lowercase status string emitted on the spans ingest payload. */
    public String wire() {
        return wire;
    }

    /**
     * Map an HTTP status code to the closest span status, matching Sentry's
     * {@code SpanStatus.fromHttpStatusCode}. 2xx/3xx ⇒ {@link #OK}; the common
     * 4xx/5xx codes map to their dedicated statuses; anything else in the
     * error range falls back to {@link #UNKNOWN} / {@link #INTERNAL_ERROR}.
     */
    public static SpanStatus fromHttpStatus(int httpStatus) {
        if (httpStatus >= 200 && httpStatus < 400) return OK;
        switch (httpStatus) {
            case 400: return INVALID_ARGUMENT;
            case 401: return UNAUTHENTICATED;
            case 403: return PERMISSION_DENIED;
            case 404: return NOT_FOUND;
            case 409: return ALREADY_EXISTS;
            case 429: return RESOURCE_EXHAUSTED;
            case 499: return CANCELLED;
            case 500: return INTERNAL_ERROR;
            case 501: return UNIMPLEMENTED;
            case 503: return UNAVAILABLE;
            case 504: return DEADLINE_EXCEEDED;
            default:
                if (httpStatus >= 400 && httpStatus < 500) return INVALID_ARGUMENT;
                if (httpStatus >= 500 && httpStatus < 600) return INTERNAL_ERROR;
                return UNKNOWN;
        }
    }
}
