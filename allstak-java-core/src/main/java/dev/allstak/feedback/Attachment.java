package dev.allstak.feedback;

/**
 * A binary attachment uploaded alongside an event. PII responsibility
 * rests with the caller — the SDK does not inspect attachment bytes.
 */
public final class Attachment {

    private final String filename;
    private final String contentType;
    private final byte[] bytes;

    public Attachment(String filename, String contentType, byte[] bytes) {
        this.filename = filename;
        this.contentType = contentType;
        this.bytes = bytes;
    }

    public String getFilename()    { return filename;    }
    public String getContentType() { return contentType; }
    public byte[] getBytes()       { return bytes;       }
    public int    getSize()        { return bytes == null ? 0 : bytes.length; }
}
