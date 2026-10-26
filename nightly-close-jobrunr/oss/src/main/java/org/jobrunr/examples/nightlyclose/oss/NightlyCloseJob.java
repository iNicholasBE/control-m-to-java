package org.jobrunr.examples.nightlyclose.oss;

import org.jobrunr.examples.nightlyclose.ChainIds;
import org.jobrunr.examples.nightlyclose.steps.NightlyCloseSteps;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.context.JobContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The nightly close on JobRunr OSS: one durable job. Each step runs at most once per successful completion;
 * when the job is retried, steps that completed are skipped and it resumes at the first one that did not.
 * The fan-out and the join live inside the "score" step: twenty partitions on virtual threads in this JVM.
 * <p>
 * OSS saves the step markers with the job on the poll interval, so after a hard crash a step that had just
 * finished can run again. Every step is idempotent for that reason.
 */
@Component
public class NightlyCloseJob {

    private final NightlyCloseSteps steps;

    public NightlyCloseJob(NightlyCloseSteps steps) {
        this.steps = steps;
    }

    @Job(name = "Nightly close %0 run %1")
    public void run(LocalDate businessDate, int runNumber, JobContext jobContext) {
        UUID runId = ChainIds.runId(businessDate, runNumber);
        jobContext.runStepOnce("extract", () -> steps.extract(runId));
        jobContext.runStepOnce("score", () -> steps.scoreAll(runId));
        jobContext.runStepOnce("aggregate", () -> steps.aggregate(runId));
        jobContext.runStepOnce("report", () -> steps.report(runId));
        jobContext.runStepOnce("deliver", () -> steps.deliver(runId));
        jobContext.runStepOnce("finish", () -> steps.finish(runId));
    }
}
