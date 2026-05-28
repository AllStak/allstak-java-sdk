package dev.allstak.debug;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves a build's debug id from {@code allstak-debug-meta.properties}
 * baked into the JAR by the AllStak Maven plugin. The debug id ties a
 * runtime stack frame back to the exact source bundle uploaded at build
 * time, so the dashboard can render source context (filename + line
 * surroundings) for symbolicated frames.
 *
 * <p>If the file isn't present the SDK silently returns {@code null} —
 * source context is a nice-to-have, never a correctness gate.
 */
public final class DebugIdResolver {

    /** Classpath resource the Maven plugin writes. */
    public static final String RESOURCE = "allstak-debug-meta.properties";

    /** Property key holding the UUID. */
    public static final String KEY_DEBUG_ID = "debug.id";

    private static final AtomicReference<String> CACHED = new AtomicReference<>();

    private DebugIdResolver() {}

    /** Returns the resolved debug id, or {@code null} if no metadata file was found. */
    public static String resolve() {
        String c = CACHED.get();
        if (c != null) return c.isBlank() ? null : c;
        String v = readFromClasspath();
        CACHED.set(v == null ? "" : v);
        return v;
    }

    private static String readFromClasspath() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = DebugIdResolver.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(RESOURCE)) {
            if (is == null) return null;
            Properties p = new Properties();
            p.load(is);
            String id = p.getProperty(KEY_DEBUG_ID);
            return id == null || id.isBlank() ? null : id.trim();
        } catch (IOException ignored) {
            return null;
        }
    }
}
