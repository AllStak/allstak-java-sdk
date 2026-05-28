package dev.allstak.quartz;

import dev.allstak.AllStak;
import dev.allstak.model.JobHandle;
import dev.allstak.scope.Scopes;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobListener;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Quartz {@link JobListener} that emits an AllStak cron check-in for every
 * job execution. Status goes {@code in-progress} → {@code ok} (on
 * vetoed/completed without error) or {@code error} (on
 * {@link JobExecutionException}).
 *
 * <p>Each execution runs inside a fresh AllStak isolation scope so
 * job-local context (job id, fire instance, retry count) doesn't bleed
 * into other jobs scheduled on the same worker thread.
 *
 * <p>Register on a scheduler:
 *
 * <pre>{@code
 * scheduler.getListenerManager()
 *     .addJobListener(new AllStakQuartzJobListener());
 * }</pre>
 */
public final class AllStakQuartzJobListener implements JobListener {

    private final ConcurrentHashMap<String, JobHandle> inFlight = new ConcurrentHashMap<>();

    @Override
    public String getName() { return "AllStakQuartzJobListener"; }

    @Override
    public void jobToBeExecuted(JobExecutionContext context) {
        String slug = slug(context);
        Scopes.withIsolationScope(s -> {
            s.setTag("quartz.group", context.getJobDetail().getKey().getGroup());
            s.setTag("quartz.name", context.getJobDetail().getKey().getName());
            s.setTag("quartz.fireInstanceId", context.getFireInstanceId());
        });
        inFlight.put(context.getFireInstanceId(), AllStak.startJob(slug));
    }

    @Override
    public void jobExecutionVetoed(JobExecutionContext context) {
        JobHandle h = inFlight.remove(context.getFireInstanceId());
        if (h != null) AllStak.finishJob(h, "ok", "vetoed");
    }

    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
        JobHandle h = inFlight.remove(context.getFireInstanceId());
        if (h == null) return;
        if (jobException != null) {
            AllStak.captureException(jobException);
            AllStak.finishJob(h, "error", jobException.getMessage());
        } else {
            AllStak.finishJob(h, "ok");
        }
    }

    private static String slug(JobExecutionContext context) {
        return context.getJobDetail().getKey().getGroup() + "/" + context.getJobDetail().getKey().getName();
    }
}
