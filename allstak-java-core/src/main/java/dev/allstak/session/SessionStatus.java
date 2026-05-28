package dev.allstak.session;

/**
 * Lifecycle status of a release-health session.
 *
 * <p>Vocabulary matches the AllStak backend's {@code /ingest/v1/sessions/end}
 * contract and Sentry's release-health conventions:
 *
 * <ul>
 *   <li>{@link #OK} — session ended normally with at most non-fatal logs.</li>
 *   <li>{@link #ERRORED} — at least one captured event of level {@code error}
 *       or higher landed during the session, but the process kept running.</li>
 *   <li>{@link #CRASHED} — an unhandled {@code fatal}-level exception ended
 *       the JVM (the SDK only reports this when it observes the uncaught
 *       exception itself).</li>
 *   <li>{@link #ABNORMAL} — JVM ended without a normal flush. Reserved for
 *       future shutdown-hook telemetry.</li>
 * </ul>
 */
public enum SessionStatus {
    OK("ok"),
    ERRORED("errored"),
    CRASHED("crashed"),
    ABNORMAL("abnormal");

    private final String wire;

    SessionStatus(String wire) { this.wire = wire; }

    /** Backend wire value — lower-case string the {@code /sessions/end} DTO expects. */
    public String wireValue() { return wire; }
}
