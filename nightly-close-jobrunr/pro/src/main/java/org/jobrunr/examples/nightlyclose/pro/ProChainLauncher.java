package org.jobrunr.examples.nightlyclose.pro;

import org.jobrunr.examples.nightlyclose.contract.ChainLauncher;
import org.jobrunr.examples.nightlyclose.contract.ChainRun;
import org.jobrunr.examples.nightlyclose.contract.ChainRunRepository;
import org.jobrunr.examples.nightlyclose.contract.RunState;
import org.jobrunr.jobs.context.JobContext;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;

import static org.jobrunr.examples.nightlyclose.ChainIds.jobId;
import static org.jobrunr.scheduling.JobBuilder.aBatchJob;
import static org.jobrunr.scheduling.JobBuilder.aJob;

/**
 * The nightly close as one batch workflow, two hops:
 * <pre>
 *   batch "extract and fan out" [mutex positions-dataset]  ->  20 child jobs, one per portfolio [rate limiter, 20]
 *     continueWith: "finish", a durable job: aggregate, report, deliver
 * </pre>
 * This is {@code startBatch(...).continueWith(...)} written with the JobBuilder, because the builder gives both jobs
 * a deterministic id: the batch gets the run id. {@code continueWith} would give the continuation a fresh id on
 * every launch, so a second launch of the same run would attach a second "finish" to the batch.
 * Pro's transaction plugin saves both jobs in the same transaction as the LAUNCHED state.
 */
@Component
class ProChainLauncher implements ChainLauncher {

    static final String POSITIONS_MUTEX = "positions-dataset";
    static final String DATABASE_HEAVY = "database-heavy";

    private final JobScheduler jobScheduler;
    private final ChainRunRepository runs;
    private final TransactionTemplate transaction;

    ProChainLauncher(JobScheduler jobScheduler, ChainRunRepository runs, TransactionTemplate transaction) {
        this.jobScheduler = jobScheduler;
        this.runs = runs;
        this.transaction = transaction;
    }

    @Override
    public void launch(ChainRun run) {
        UUID runId = run.runId();
        LocalDate businessDate = run.businessDate();
        int runNumber = run.runNumber();
        String name = "Nightly close " + businessDate + " run " + runNumber + ": ";
        transaction.executeWithoutResult(status -> {
            jobScheduler.create(Stream.of(
                    aBatchJob().withId(runId).withName(name + "extract, score per portfolio")
                            .withMutex(POSITIONS_MUTEX)
                            .<ProChainJobs>withJobLambda(jobs -> jobs.extractAndFanOut(businessDate, runNumber, JobContext.Null)),
                    aJob().withId(jobId(runId, "finish")).withName(name + "aggregate, report, deliver")
                            .runAfterSuccessOf(runId)
                            .<ProChainJobs>withJobLambda(jobs -> jobs.finish(businessDate, runNumber, JobContext.Null))));
            runs.advance(runId, RunState.SUBMITTED, RunState.LAUNCHED);
        });
    }
}
