package dev.allstak.feedback;

/**
 * A binary attachment uploaded alongside a previously captured error
 * event. Tied to that event via {@code eventId} so the dashboard renders
 * the attachment inline on the issue page.
 *
 * <p>PII responsibility rests with the caller — the SDK does not inspect
 * attachment bytes beyond base64-encoding them for transit. The backend
 * caps the decoded size and rejects disallowed content types.
 */
public final class Attachment {

    private final String eventId;
    private final String kind;
    private final String contentType;
    private final byte[] bytes;

    public Attachment(String eventId, String kind, String contentType, byte[] bytes) {
        this.eventId = eventId;
        this.kind = kind;
        this.contentType = contentType;
        this.bytes = bytes;
    }

    /** Id of the {@link dev.allstak.model.ErrorEvent} this attachment belongs to. */
    public String getEventId()     { return eventId;     }
    /** Free-form attachment category — e.g. {@code "screenshot"}, {@code "log"}. */
    public String getKind()        { return kind;        }
    public String getContentType() { return contentType; }
    public byte[] getBytes()       { return bytes;       }
    public int    getSize()        { return bytes == null ? 0 : bytes.length; }
}
