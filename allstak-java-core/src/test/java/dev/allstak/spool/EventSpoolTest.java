package dev.allstak.spool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the filesystem {@link EventSpool}: persist/load roundtrip,
 * the count and byte caps (drop-oldest eviction), max-age pruning, and the
 * graceful no-op when the backing directory is unavailable.
 */
class EventSpoolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode node(String key, String value) {
        return MAPPER.createObjectNode().put(key, value);
    }

    @Test
    void persistThenLoad_roundTripsPathAndPayload(@TempDir Path dir) {
        EventSpool spool = new EventSpool(dir, 100, 1_000_000, 60_000, MAPPER);
        assertThat(spool.isAvailable()).isTrue();

        assertThat(spool.persist("/ingest/v1/logs", node("message", "hello"))).isTrue();

        List<EventSpool.Handle> loaded = spool.load();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).path()).isEqualTo("/ingest/v1/logs");
        assertThat(loaded.get(0).payload().get("message").asText()).isEqualTo("hello");
    }

    @Test
    void countCap_dropsOldestFirst(@TempDir Path dir) {
        // Cap at 3 entries; write 5 — only the newest 3 survive (drop-oldest).
        EventSpool spool = new EventSpool(dir, 3, 1_000_000, 60_000, MAPPER);
        for (int i = 0; i < 5; i++) {
            assertThat(spool.persist("/ingest/v1/logs", node("seq", "evt-" + i))).isTrue();
        }

        List<EventSpool.Handle> loaded = spool.load();
        assertThat(loaded).hasSize(3);
        // Oldest (evt-0, evt-1) evicted; evt-2..4 remain, oldest-first.
        assertThat(loaded.get(0).payload().get("seq").asText()).isEqualTo("evt-2");
        assertThat(loaded.get(2).payload().get("seq").asText()).isEqualTo("evt-4");
    }

    @Test
    void byteCap_dropsOldestUntilFits(@TempDir Path dir) throws Exception {
        // Each entry is well over 100 bytes once JSON-wrapped; a ~600 byte cap
        // keeps only the last couple of entries.
        String big = "x".repeat(300);
        EventSpool spool = new EventSpool(dir, 1000, 600, 60_000, MAPPER);
        for (int i = 0; i < 6; i++) {
            spool.persist("/ingest/v1/logs", node("blob", big + i));
        }

        long totalBytes = 0;
        for (EventSpool.Handle h : spool.load()) {
            totalBytes += MAPPER.writeValueAsBytes(h.payload()).length;
        }
        // The byte cap held: not everything was kept and we stayed bounded.
        assertThat(spool.size()).isLessThan(6);
        assertThat(totalBytes).isLessThanOrEqualTo(600);
    }

    @Test
    void oversizedEntry_isDroppedNotPersisted(@TempDir Path dir) {
        EventSpool spool = new EventSpool(dir, 100, 50, 60_000, MAPPER);
        // A single entry larger than the entire budget can never coexist — drop.
        boolean ok = spool.persist("/ingest/v1/logs", node("blob", "y".repeat(500)));
        assertThat(ok).isFalse();
        assertThat(spool.size()).isZero();
    }

    @Test
    void maxAge_prunesStaleEntriesOnLoad(@TempDir Path dir) throws Exception {
        EventSpool spool = new EventSpool(dir, 100, 1_000_000, 60_000, MAPPER);
        spool.persist("/ingest/v1/logs", node("message", "fresh"));

        // Back-date the file's mtime well beyond the max age so the next load
        // prunes it.
        try (var stream = Files.newDirectoryStream(dir, "evt-*.json")) {
            for (Path p : stream) {
                Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - 5_000_000));
            }
        }

        assertThat(spool.load()).isEmpty();
        assertThat(spool.size()).isZero();
    }

    @Test
    void unavailableDir_degradesToSilentNoOp(@TempDir Path dir) throws Exception {
        // Point the spool at a path that is a regular file, not a directory, so
        // it cannot be used as a spool. Everything must no-op without throwing.
        Path notADir = dir.resolve("a-file");
        Files.writeString(notADir, "i am a file");

        EventSpool spool = new EventSpool(notADir, 100, 1_000_000, 60_000, MAPPER);

        assertThat(spool.isAvailable()).isFalse();
        assertThat(spool.persist("/ingest/v1/logs", node("message", "x"))).isFalse();
        assertThat(spool.load()).isEmpty();
        assertThat(spool.size()).isZero();
    }

    @Test
    void remove_deletesEntry(@TempDir Path dir) {
        EventSpool spool = new EventSpool(dir, 100, 1_000_000, 60_000, MAPPER);
        spool.persist("/ingest/v1/errors", node("message", "boom"));

        List<EventSpool.Handle> loaded = spool.load();
        assertThat(loaded).hasSize(1);
        spool.remove(loaded.get(0));

        assertThat(spool.size()).isZero();
        assertThat(spool.load()).isEmpty();
    }
}
