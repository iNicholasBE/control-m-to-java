package org.jobrunr.examples.nightlyclose.pro;

import org.jobrunr.examples.nightlyclose.steps.NightlyCloseSteps;
import org.jobrunr.examples.nightlyclose.steps.Partition;
import org.jobrunr.jobs.context.JobContext;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.stereotype.Component;

import java.util.UUID;

import static org.jobrunr.examples.nightlyclose.ChainIds.jobId;
import static org.jobrunr.scheduling.JobBuilder.aJob;

/**
 * One job per Control-M job. The order, the fan-out and the join live in the chain that {@link ProChainLauncher}
 * creates, so these methods only do the work.
 */
@Component
public class ProChainJobs {

    private final JobScheduler jobScheduler;
    private final NightlyCloseSteps steps;

    public ProChainJobs(JobScheduler jobScheduler, NightlyCloseSteps steps) {
        this.jobScheduler = jobScheduler;
        this.steps = steps;
    }

    /** Grouped shape: steps 1 and 2 as durable steps. Pro saves each completed step right away. */
    public void head(UUID runId, JobContext jobContext) {
        jobContext.runStepOnce("watcher", () -> steps.watch(runId));
        jobContext.runStepOnce("extract", () -> steps.extract(runId));
    }

    /** Grouped shape: steps 4 to 6 as durable steps. */
    public void tail(UUID runId, JobContext jobContext) {
        jobContext.runStepOnce("aggregate", () -> steps.aggregate(runId));
        jobContext.runStepOnce("report", () -> steps.report(runId));
        jobContext.runStepOnce("deliver", () -> steps.deliver(runId));
        jobContext.runStepOnce("finish", () -> steps.finish(runId));
    }

    public void watch(UUID runId) throws Exception {
        steps.watch(runId);
    }

    public void extract(UUID runId) throws Exception {
        steps.extract(runId);
    }

    /**
     * The batch job's body. Every job created here becomes a child of the batch: the children start once this method
     * returns, and the batch (and so its continuation, the aggregate) succeeds when all children succeeded.
     * If this method fails halfway, JobRunr deletes the children it created before retrying it.
     */
    public void fanOut(UUID runId) {
        for (Partition partition : steps.partitions(runId)) {
            int number = partition.number();
            String portfolio = partition.portfolio();
            jobScheduler.create(aJob()
                    .withId(jobId(runId, partition.stepName()))
                    .withName("Nightly close: score portfolio " + portfolio + " (partition " + number + ")")
                    .withRateLimiter(ProChainLauncher.DATABASE_HEAVY)
                    .<ProChainJobs>withJobLambda(jobs -> jobs.score(runId, number, portfolio)));
        }
        steps.startScoring(runId);
    }

    public void score(UUID runId, int partition, String portfolio) throws Exception {
        steps.score(runId, new Partition(partition, portfolio));
        // The batch is the join. This only records when the last partition finished, for the status endpoint
        // and the kill test's "join" marker; JobRunr starts the aggregate on its own.
        if (steps.joinWhenAllPartitionsDone(runId)) steps.join(runId, () -> {
        });
    }

    public void aggregate(UUID runId) throws Exception {
        steps.aggregate(runId);
    }

    public void report(UUID runId) throws Exception {
        steps.report(runId);
    }

    public void deliver(UUID runId) throws Exception {
        steps.deliver(runId);
    }

    public void finish(UUID runId) throws Exception {
        steps.finish(runId);
    }
}
