package org.jobrunr.examples.nightlyclose.pro;

import org.jobrunr.examples.nightlyclose.ChainIds;
import org.jobrunr.examples.nightlyclose.steps.NightlyCloseSteps;
import org.jobrunr.examples.nightlyclose.steps.Partition;
import org.jobrunr.jobs.context.JobContext;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

import static org.jobrunr.examples.nightlyclose.ChainIds.jobId;
import static org.jobrunr.scheduling.JobBuilder.aJob;

@Component
public class ProChainJobs {

    private final JobScheduler jobScheduler;
    private final NightlyCloseSteps steps;
    private final boolean rateLimitPartitions;

    public ProChainJobs(JobScheduler jobScheduler, NightlyCloseSteps steps,
                        @Value("${nightly-close.pro.rate-limit-partitions:true}") boolean rateLimitPartitions) {
        this.jobScheduler = jobScheduler;
        this.steps = steps;
        this.rateLimitPartitions = rateLimitPartitions;
    }

    /**
     * The batch job's body. Extract is a durable step: Pro saves its marker the moment it finishes, so a retry
     * of this job does not load the file again. The fan-out is not a step on purpose: when this method fails,
     * JobRunr deletes the children it already created before the retry, so the retry must create them again.
     * The children start once this method returns; the batch succeeds when all of them succeeded.
     */
    public void extractAndFanOut(LocalDate businessDate, int runNumber, JobContext jobContext) {
        UUID runId = ChainIds.runId(businessDate, runNumber);
        jobContext.runStepOnce("extract", () -> steps.extract(runId));
        for (Partition partition : steps.partitions(runId)) {
            int number = partition.number();
            String portfolio = partition.portfolio();
            var child = aJob()
                    .withId(jobId(runId, partition.stepName()))
                    .withName("Nightly close " + businessDate + ": score portfolio " + portfolio + " (partition " + number + ")")
                    .<ProChainJobs>withJobLambda(jobs -> jobs.score(runId, number, portfolio));
            if (rateLimitPartitions) child.withRateLimiter(ProChainLauncher.DATABASE_HEAVY);
            jobScheduler.create(child);
        }
        steps.startScoring(runId);
    }

    public void score(UUID runId, int partition, String portfolio) throws Exception {
        steps.score(runId, new Partition(partition, portfolio));
        // The batch is the join; this only records when the last partition finished.
        steps.joinIfAllPartitionsDone(runId);
    }

    /** The batch's continuation: a durable job, each step's marker saved the moment the step finishes. */
    public void finish(LocalDate businessDate, int runNumber, JobContext jobContext) {
        UUID runId = ChainIds.runId(businessDate, runNumber);
        jobContext.runStepOnce("aggregate", () -> steps.aggregate(runId));
        jobContext.runStepOnce("report", () -> steps.report(runId));
        jobContext.runStepOnce("deliver", () -> steps.deliver(runId));
        jobContext.runStepOnce("finish", () -> steps.finish(runId));
    }
}
