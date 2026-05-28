package dev.allstak.tracing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Decides whether an outbound HTTP target should receive AllStak trace
 * headers (and, by extension, leak the trace context to that target). The
 * SDK consults this from every outbound-HTTP integration (RestTemplate,
 * WebClient, OkHttp, etc.) before injecting {@code x-allstak-trace-id} or
 * the W3C {@code traceparent}.
 *
 * <p>Matches Sentry's {@code tracePropagationTargets}:
 *
 * <ul>
 *   <li>Null/empty list ⇒ propagate to ALL targets (the default).</li>
 *   <li>Otherwise ⇒ propagate iff the request URL (or host alone) matches
 *       at least one of the configured patterns by regex or substring.</li>
 * </ul>
 *
 * <p>Patterns are tested as regex first; a non-regex literal is matched as
 * a case-insensitive substring of the request URL — so customers can write
 * either {@code "^https://api\\.example\\.com/"} or just
 * {@code "api.example.com"} and both work.
 */
public final class TracePropagationDecider {

    private final List<Pattern> compiled;
    private final List<String> literals;
    private final boolean propagateAll;

    public TracePropagationDecider(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            this.compiled = List.of();
            this.literals = List.of();
            this.propagateAll = true;
            return;
        }
        List<Pattern> ps = new ArrayList<>();
        List<String> lits = new ArrayList<>();
        for (String p : patterns) {
            if (p == null || p.isBlank()) continue;
            try {
                ps.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException ignored) {
                lits.add(p.toLowerCase());
            }
        }
        this.compiled = List.copyOf(ps);
        this.literals = List.copyOf(lits);
        this.propagateAll = ps.isEmpty() && lits.isEmpty();
    }

    /**
     * Should the SDK inject trace headers on a request to {@code targetUrl}?
     * {@code null} URL ⇒ false (we cannot decide → safest is don't leak).
     */
    public boolean shouldPropagate(String targetUrl) {
        if (propagateAll) return true;
        if (targetUrl == null || targetUrl.isBlank()) return false;
        for (Pattern p : compiled) {
            if (p.matcher(targetUrl).find()) return true;
        }
        String lower = targetUrl.toLowerCase();
        for (String lit : literals) {
            if (lower.contains(lit)) return true;
        }
        return false;
    }

    /** Default (no patterns configured) — propagate everywhere. */
    public static TracePropagationDecider propagateAll() {
        return new TracePropagationDecider(null);
    }
}
