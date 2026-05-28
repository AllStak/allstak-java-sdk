package dev.allstak.profiling_jfr;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import dev.allstak.internal.SdkLogger;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Continuous JVM profiler backed by Java Flight Recorder. JFR ships with
 * every modern JDK (11+), so this module doesn't bundle native libraries
 * — the trade-off vs async-profiler is slightly higher overhead (~1-2%)
 * but instant cross-platform support including Windows.
 *
 * <p>Lifecycle:
 *
 * <pre>{@code
 * AllStakJfrProfiler profiler = AllStakJfrProfiler.start();
 * // ... app runs ...
 * profiler.stop();
 * }</pre>
 *
 * <p>Spring Boot starter auto-starts/stops with the application context
 * when this module is on the classpath.
 */
public final class AllStakJfrProfiler {

    /** Chunk rotation interval — same window AllStak's backend bucketizes profiles by. */
    public static final Duration CHUNK_INTERVAL = Duration.ofSeconds(30);

    private final Recording recording;
    private final ScheduledExecutorService scheduler;
    private final Path workDir;
    private final String chunkPath = "/ingest/v1/profiles";
    private volatile boolean stopped = false;

    private AllStakJfrProfiler(Recording recording, Path workDir) {
        this.recording = recording;
        this.workDir = workDir;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "allstak-jfr-profiler");
            t.setDaemon(true);
            return t;
        });
    }

    public static AllStakJfrProfiler start() {
        try {
            Configuration cfg = Configuration.getConfiguration("profile");
            Recording recording = new Recording(cfg);
            recording.setName("allstak");
            recording.setMaxAge(CHUNK_INTERVAL.multipliedBy(2));
            recording.setMaxSize(50L * 1024 * 1024);
            Path workDir = Files.createTempDirectory("allstak-jfr-");
            recording.start();
            AllStakJfrProfiler p = new AllStakJfrProfiler(recording, workDir);
            p.scheduleRotation();
            return p;
        } catch (IOException | java.text.ParseException e) {
            SdkLogger.debug("JFR profiler failed to start: {}", e.getMessage());
            return null;
        }
    }

    public void stop() {
        if (stopped) return;
        stopped = true;
        try {
            recording.stop();
            recording.close();
        } catch (Exception ignored) { }
        scheduler.shutdownNow();
        try { Files.walk(workDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} }); } catch (IOException ignored) {}
    }

    private void scheduleRotation() {
        scheduler.scheduleAtFixedRate(this::rotateChunk,
                CHUNK_INTERVAL.toSeconds(), CHUNK_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    private void rotateChunk() {
        if (stopped) return;
        AllStakClient client = AllStak.getClient();
        if (client == null) return;
        try {
            Path chunkFile = workDir.resolve("chunk-" + System.currentTimeMillis() + ".jfr");
            recording.dump(chunkFile);
            byte[] bytes = Files.readAllBytes(chunkFile);
            Files.deleteIfExists(chunkFile);
            // Wrap bytes in a JSON envelope the backend recognises. Profile
            // payloads are opaque binary — we base64-encode for transit
            // (no streaming upload yet to keep the transport simple).
            String envelope = "{\"format\":\"jfr\",\"chunkBase64\":\""
                    + java.util.Base64.getEncoder().encodeToString(bytes) + "\"}";
            client.getTransport().send(chunkPath, envelope);
        } catch (Throwable t) {
            SdkLogger.debug("JFR chunk rotation failed: {}", t.getMessage());
        }
    }
}
