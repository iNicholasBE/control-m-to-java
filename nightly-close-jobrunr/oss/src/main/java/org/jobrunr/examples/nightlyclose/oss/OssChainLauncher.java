package org.jobrunr.examples.nightlyclose.oss;

import org.jobrunr.examples.nightlyclose.contract.ChainLauncher;
import org.jobrunr.examples.nightlyclose.contract.ChainRun;
import org.jobrunr.examples.nightlyclose.contract.ChainRunRepository;
import org.jobrunr.examples.nightlyclose.contract.RunState;
import org.jobrunr.jobs.context.JobContext;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Starts a run by enqueueing its head job under the run id. JobRunr ignores a job whose id it already has,
 * so launching twice (after a crash between the two lines below) still runs the chain once.
 */
@Component
class OssChainLauncher implements ChainLauncher {

    private final JobScheduler jobScheduler;
    private final ChainRunRepository runs;

    OssChainLauncher(JobScheduler jobScheduler, ChainRunRepository runs) {
        this.jobScheduler = jobScheduler;
        this.runs = runs;
    }

    @Override
    public void launch(ChainRun run) {
        UUID runId = run.runId();
        LocalDate businessDate = run.businessDate();
        int runNumber = run.runNumber();
        jobScheduler.<OssChainJobs>enqueue(runId, jobs -> jobs.head(runId, businessDate, runNumber, JobContext.Null));
        runs.advance(runId, RunState.SUBMITTED, RunState.LAUNCHED);
    }
}
