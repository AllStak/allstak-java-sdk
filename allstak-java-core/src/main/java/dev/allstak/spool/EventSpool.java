package dev.allstak.spool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.allstak.internal.SdkLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Filesystem spool that lets buffered telemetry survive a process restart or a
 * network outage. One JSON file per envelope under a configurable cache
 * directory; each file is a {@link SpoolEntry} (ingest path + scrubbed payload
 * + creation stamp). On the next SDK init the spool is drained and replayed
 * through the normal transport.
 *
 * <p><b>Scrub-before-persist:</b> the spool never scrubs — it stores the bytes
 * it is handed. The caller (AllStakClient) is responsible for running the PII
 * sanitizer before {@link #persist(String, JsonNode)}, exactly as it does
 * before a live send. This keeps a single masking authority and guarantees no
 * unredacted payload reaches disk.
 *
 * <p><b>Bounded:</b> the store is capped by entry count, total bytes, and max
 * age. When a write would exceed the count/byte cap the oldest files are
 * evicted first (FIFO drop-oldest, matching the in-memory ring buffer). Aged
 * entries are pruned on init and opportunistically on write.
 *
 * <p><b>Fail-open:</b> if the directory cannot be created or is not writable
 * (read-only FS, serverless, sandbox) the spool degrades to a silent no-op so
 * the SDK falls back to its existing in-memory behavior. No method ever throws
 * and no method blocks on network I/O.
 */
public final class EventSpool {

    /** Default cap on persisted envelopes for server runtimes. */
    public static final int DEFAULT_MAX_ENTRIES = 200;
    /** Default cap on total spool bytes (a few MB for servers). */
    public static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;
    /** Default max age before a persisted envelope is considered stale (48h). */
    public static final long DEFAULT_MAX_AGE_MS = 48L * 60 * 60 * 1000;

    private static final String FILE_PREFIX = "evt-";
    private static final String FILE_SUFFIX = ".json";

    private final Path dir;
    private final int maxEntries;
    private final long maxBytes;
    private final long maxAgeMs;
    private final ObjectMapper mapper;
    private final ReentrantLock lock = new ReentrantLock();
    // Monotonic suffix so two writes within the same millisecond keep ordering.
    private final AtomicLong seq = new AtomicLong(0);

    private final boolean available;

    public EventSpool(Path dir, int maxEntries, long maxBytes, long maxAgeMs, ObjectMapper mapper) {
        this.dir = dir;
        this.maxEntries = maxEntries > 0 ? maxEntries : DEFAULT_MAX_ENTRIES;
        this.maxBytes = maxBytes > 0 ? maxBytes : DEFAULT_MAX_BYTES;
        this.maxAgeMs = maxAgeMs > 0 ? maxAgeMs : DEFAULT_MAX_AGE_MS;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
        this.available = probe(dir);
        if (available) {
            SdkLogger.debug("Offline spool ready at {} (maxEntries={}, maxBytes={}, maxAgeMs={})",
                    dir, this.maxEntries, this.maxBytes, this.maxAgeMs);
        } else {
            SdkLogger.debug("Offline spool unavailable at {} — degrading to in-memory only", dir);
        }
    }

    /**
     * Best-effort directory probe. Creates the dir if missing and confirms it
     * is writable. Any failure (read-only FS, permission, sandbox) flips the
     * spool to a silent no-op. Never throws.
     */
    private static boolean probe(Path dir) {
        if (dir == null) return false;
        try {
            Files.createDirectories(dir);
            return Files.isDirectory(dir) && Files.isWritable(dir);
        } catch (Throwable t) {
            SdkLogger.debug("Offline spool dir not usable ({}): {}", dir, t.getMessage());
            return false;
        }
    }

    /** Whether the spool is backed by a usable directory. False ⇒ all ops no-op. */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Persist one already-scrubbed envelope. The payload MUST already have been
     * run through the PII sanitizer by the caller. Returns true if it was
     * written, false on no-op (unavailable) or any failure. Never throws.
     */
    public boolean persist(String path, JsonNode scrubbedPayload) {
        if (!available || path == null || scrubbedPayload == null) return false;
        lock.lock();
        try {
            SpoolEntry entry = new SpoolEntry(path, System.currentTimeMillis(), scrubbedPayload);
            byte[] bytes = mapper.writeValueAsBytes(entry);
            // A single envelope larger than the whole budget is dropped rather
            // than persisted — it could never coexist with anything else.
            if (bytes.length > maxBytes) {
                SdkLogger.debug("Spool entry for {} exceeds maxBytes ({}>{}) — dropping", path, bytes.length, maxBytes);
                return false;
            }
            String name = FILE_PREFIX + System.currentTimeMillis() + "-"
                    + String.format("%012d", seq.incrementAndGet()) + FILE_SUFFIX;
            Path tmp = dir.resolve(name + ".tmp");
            Path target = dir.resolve(name);
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            enforceBounds();
            return true;
        } catch (Throwable t) {
            SdkLogger.debug("Spool persist failed for {}: {}", path, t.getMessage());
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Load every currently-spooled entry, oldest first, pruning stale ones as a
     * side effect. The returned handles let the drainer remove an entry only
     * after it is accepted or permanently undeliverable. Never throws; returns
     * an empty list on no-op or error.
     */
    public List<Handle> load() {
        if (!available) return List.of();
        lock.lock();
        try {
            pruneAged();
            List<Path> files = listFilesOldestFirst();
            List<Handle> handles = new ArrayList<>(files.size());
            for (Path f : files) {
                try {
                    byte[] bytes = Files.readAllBytes(f);
                    SpoolEntry entry = mapper.readValue(bytes, SpoolEntry.class);
                    if (entry == null || entry.getPath() == null || entry.getPayload() == null) {
                        // Corrupt/incomplete — drop it so it can't wedge the drain.
                        deleteQuietly(f);
                        continue;
                    }
                    handles.add(new Handle(f, entry));
                } catch (Throwable t) {
                    SdkLogger.debug("Spool entry {} unreadable — dropping: {}", f.getFileName(), t.getMessage());
                    deleteQuietly(f);
                }
            }
            return handles;
        } catch (Throwable t) {
            SdkLogger.debug("Spool load failed: {}", t.getMessage());
            return List.of();
        } finally {
            lock.unlock();
        }
    }

    /** Remove a drained entry once it has been accepted or permanently rejected. */
    public void remove(Handle handle) {
        if (handle == null) return;
        deleteQuietly(handle.file);
    }

    /** Current number of spooled entries. 0 when unavailable. Never throws. */
    public int size() {
        if (!available) return 0;
        lock.lock();
        try {
            return listFilesOldestFirst().size();
        } catch (Throwable t) {
            return 0;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------------
    // Internal
    // ---------------------------------------------------------------------

    private void enforceBounds() {
        try {
            List<Path> files = listFilesOldestFirst();
            // Count bound — drop oldest until at or under the cap.
            while (files.size() > maxEntries) {
                Path oldest = files.remove(0);
                deleteQuietly(oldest);
            }
            // Byte bound — drop oldest until total size fits.
            long total = 0;
            for (Path f : files) total += sizeQuietly(f);
            int i = 0;
            while (total > maxBytes && i < files.size()) {
                Path oldest = files.get(i++);
                total -= sizeQuietly(oldest);
                deleteQuietly(oldest);
            }
        } catch (Throwable t) {
            SdkLogger.debug("Spool bound enforcement failed: {}", t.getMessage());
        }
    }

    private void pruneAged() {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        for (Path f : listFilesOldestFirst()) {
            try {
                if (Files.getLastModifiedTime(f).toMillis() < cutoff) {
                    deleteQuietly(f);
                }
            } catch (Throwable ignore) {
                // leave it; a later read will drop it if truly broken
            }
        }
    }

    /**
     * Files sorted oldest-first. Ordering is by filename, which embeds the
     * creation timestamp + a zero-padded monotonic sequence, so lexicographic
     * order equals creation order even within the same millisecond. {@code .tmp}
     * staging files are excluded.
     */
    private List<Path> listFilesOldestFirst() {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, FILE_PREFIX + "*" + FILE_SUFFIX)) {
            for (Path p : stream) {
                if (p.getFileName().toString().endsWith(".tmp")) continue;
                files.add(p);
            }
        } catch (Throwable t) {
            SdkLogger.debug("Spool listing failed: {}", t.getMessage());
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return files;
    }

    private static long sizeQuietly(Path f) {
        try {
            return Files.size(f);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void deleteQuietly(Path f) {
        try {
            Files.deleteIfExists(f);
        } catch (Throwable t) {
            SdkLogger.debug("Spool delete failed for {}: {}", f, t.getMessage());
        }
    }

    /**
     * Default cache directory for server runtimes:
     * {@code ${java.io.tmpdir}/allstak-spool/<apiKeyHash>}. Keyed by the API key
     * hash so two services on one host don't replay each other's events. Never
     * throws — returns a path even if the dir does not yet exist.
     */
    public static Path defaultDir(String apiKey) {
        String base = System.getProperty("java.io.tmpdir", ".");
        String slot = "default";
        if (apiKey != null && !apiKey.isBlank()) {
            slot = Integer.toHexString(apiKey.hashCode());
        }
        return Paths.get(base, "allstak-spool", slot);
    }

    /** Resolve a configured directory string, or fall back to {@link #defaultDir}. */
    public static Path resolveDir(String configured, String apiKey) {
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        return defaultDir(apiKey);
    }

    /** A loaded entry plus its backing file, so the drainer can remove it. */
    public static final class Handle {
        private final Path file;
        private final SpoolEntry entry;

        Handle(Path file, SpoolEntry entry) {
            this.file = file;
            this.entry = entry;
        }

        public String path() { return entry.getPath(); }
        public JsonNode payload() { return entry.getPayload(); }
        public long createdAt() { return entry.getCreatedAt(); }
    }
}
