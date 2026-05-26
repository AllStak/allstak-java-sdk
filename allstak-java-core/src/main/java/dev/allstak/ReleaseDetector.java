package dev.allstak;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * CI-free automatic release detection (step 3 of release resolution).
 *
 * <p>The runtime mechanism is a guarded {@code git describe --tags --always
 * --dirty} shell-out, run at most once and cached. It is seamable: the parse
 * logic ({@link #parse(GitRunner)}) takes a {@link GitRunner} so tests inject
 * canned output or a throwing runner without needing a real repo.
 *
 * <p><b>Production note:</b> a shipped jar has no {@code .git} directory, so the
 * runtime git shell-out only resolves a release when the app is run from a
 * source checkout. The robust production mechanism is a <i>build-time embed</i>:
 * a Maven resource-filtering / {@code git-commit-id-plugin} step that bakes the
 * commit SHA into a generated properties file (or the jar manifest) read at
 * runtime. That is the recommended path for published artifacts; this runtime
 * detector is the requested no-CI fallback for local/source runs, with the
 * SDK-version constant as the final guarantee that release is never empty.
 */
final class ReleaseDetector {

    private ReleaseDetector() {}

    /**
     * Runs a git command and returns trimmed stdout, or {@code null} on any
     * failure (git missing, not a repo, timeout, non-zero exit). Implementations
     * must never throw for expected failures; the parse layer treats both
     * {@code null} and a thrown exception as "no release".
     */
    interface GitRunner {
        String run(List<String> args) throws Exception;
    }

    /**
     * Default runner: shells out to {@code git} with a short timeout. Swallows
     * nothing here — it returns {@code null} or throws; {@link #parse} handles
     * both gracefully.
     */
    static final GitRunner DEFAULT_GIT_RUNNER = args -> {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(false);
        Process proc = pb.start();
        boolean finished = proc.waitFor(2, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            return null;
        }
        if (proc.exitValue() != 0) {
            return null;
        }
        byte[] out = proc.getInputStream().readAllBytes();
        return new String(out, java.nio.charset.StandardCharsets.UTF_8).trim();
    };

    /**
     * Pure, seamable parse: derives the release from {@code git describe}. Any
     * exception or null/blank output yields {@code null} so the caller falls
     * through to the version fallback. Tests pass a fake {@link GitRunner}.
     */
    static String parse(GitRunner runner) {
        if (runner == null) return null;
        try {
            String out = runner.run(List.of("git", "describe", "--tags", "--always", "--dirty"));
            if (out == null) return null;
            String trimmed = out.trim();
            return trimmed.isEmpty() ? null : trimmed;
        } catch (Throwable ignore) {
            // Fail-open: detection is best-effort and must never break init.
            return null;
        }
    }

    /** Cached result of the default-runner detection (computed at most once). */
    private static volatile String cached;
    private static volatile boolean cachedComputed;

    /**
     * Cached convenience over {@link #parse(GitRunner)} using the default git
     * runner. The shell-out runs at most once per process. Unit tests exercise
     * {@link #parse(GitRunner)} directly with a fake runner instead of this
     * cache, so the parse logic needs no real repo.
     */
    static String detectCached() {
        if (!cachedComputed) {
            synchronized (ReleaseDetector.class) {
                if (!cachedComputed) {
                    cached = parse(DEFAULT_GIT_RUNNER);
                    cachedComputed = true;
                }
            }
        }
        return cached;
    }
}
