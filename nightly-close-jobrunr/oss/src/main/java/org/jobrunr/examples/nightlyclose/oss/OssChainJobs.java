package org.jobrunr.examples.nightlyclose.oss;

import org.jobrunr.examples.nightlyclose.ChainIds;
import org.jobrunr.examples.nightlyclose.contract.ChainRun;
import org.jobrunr.examples.nightlyclose.contract.ChainRunRepository;
import org.jobrunr.examples.nightlyclose.steps.NightlyCloseSteps;
import org.jobrunr.examples.nightlyclose.steps.Partition;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.jobrunr.jobs.context.JobContext;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The nightly close on JobRunr OSS: two durable jobs around a fan-out, joined by a polling job.
 * <pre>
 *   head:  watcher -> extract -> fan-out      (runStepOnce per step)
 *   20 x   scorePartition                     (one job per portfolio, deterministic ids)
 *   join:  recurring poller on partition_progress, enqueues the tail once all partitions are in
 *   tail:  aggregate -> report -> deliver -> finish   (runStepOnce per step)
 * </pre>
 * The business date and run number are job parameters: they are only there to name the jobs in the dashboard.
 * The steps read everything else from chain_runs by run id.
 */
@Component
public class OssChainJobs {

    private final JobScheduler jobScheduler;
    private final NightlyCloseSteps steps;
    private final ChainRunRepository runs;

    public OssChainJobs(JobScheduler jobScheduler, NightlyCloseSteps steps, ChainRunRepository runs) {
        this.jobScheduler = jobScheduler;
        this.steps = steps;
        this.runs = runs;
    }

    @Job(name = "Nightly close %1 run %2: watcher, extract, fan-out")
    public void head(UUID runId, LocalDate businessDate, int runNumber, JobContext jobContext) {
        jobContext.runStepOnce("watcher", () -> steps.watch(runId));
        jobContext.runStepOnce("extract", () -> steps.extract(runId));
        jobContext.runStepOnce("fan-out", () -> fanOut(runId));
    }

    @Job(name = "Nightly close: score portfolio %2 (partition %1)")
    public void scorePartition(UUID runId, int partition, String portfolio) throws Exception {
        steps.score(runId, new Partition(partition, portfolio));
    }

    /**
     * The join, written by hand because OSS has no batches: every 5 seconds, find runs whose partitions are all in
     * {@code partition_progress} and enqueue their tail. The tail's id is derived from the run id, so a poll that
     * repeats after a crash cannot start the tail twice.
     */
    @Recurring(id = "nightly-close-join", interval = "PT5S")
    @Job(name = "Nightly close: join scored partitions")
    public void joinPoller() throws Exception {
        for (UUID runId : runs.findScoringRunsWithAllPartitionsDone()) {
            ChainRun run = runs.get(runId);
            LocalDate businessDate = run.businessDate();
            int runNumber = run.runNumber();
            steps.join(runId, () -> {
                jobScheduler.<OssChainJobs>enqueue(ChainIds.jobId(runId, "tail"),
                        jobs -> jobs.tail(runId, businessDate, runNumber, JobContext.Null));
                steps.joinWhenAllPartitionsDone(runId);
            });
        }
    }

    @Job(name = "Nightly close %1 run %2: aggregate, report, deliver")
    public void tail(UUID runId, LocalDate businessDate, int runNumber, JobContext jobContext) {
        jobContext.runStepOnce("aggregate", () -> steps.aggregate(runId));
        jobContext.runStepOnce("report", () -> steps.report(runId));
        jobContext.runStepOnce("deliver", () -> steps.deliver(runId));
        jobContext.runStepOnce("finish", () -> steps.finish(runId));
    }

    private void fanOut(UUID runId) {
        for (Partition partition : steps.partitions(runId)) {
            int number = partition.number();
            String portfolio = partition.portfolio();
            jobScheduler.<OssChainJobs>enqueue(ChainIds.jobId(runId, partition.stepName()),
                    jobs -> jobs.scorePartition(runId, number, portfolio));
        }
        steps.startScoring(runId);
    }
}
