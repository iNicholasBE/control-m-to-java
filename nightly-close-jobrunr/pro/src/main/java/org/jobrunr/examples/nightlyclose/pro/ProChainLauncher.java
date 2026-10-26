package org.jobrunr.examples.nightlyclose.pro;

import org.jobrunr.examples.nightlyclose.contract.ChainLauncher;
import org.jobrunr.examples.nightlyclose.contract.ChainRun;
import org.jobrunr.examples.nightlyclose.contract.ChainRunRepository;
import org.jobrunr.examples.nightlyclose.contract.RunState;
import org.jobrunr.jobs.context.JobContext;
import org.jobrunr.scheduling.JobBuilder;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.stream.Stream;

import static org.jobrunr.examples.nightlyclose.ChainIds.jobId;
import static org.jobrunr.scheduling.JobBuilder.aBatchJob;
import static org.jobrunr.scheduling.JobBuilder.aJob;

/**
 * The whole Control-M flow, declared up front as one job chain. Two shapes, picked with {@code nightly-close.pro.chain-shape}:
 * <pre>
 *   per-step (default, one JobRunr job per Control-M job):
 *     watcher -> extract [mutex] -> scoring batch [20 children, rate limited] -> aggregate [rate limited] -> report -> deliver -> finish
 *   grouped (durable steps inside two jobs around the batch):
 *     head: watcher, extract [mutex] -> scoring batch [20 children, rate limited] -> tail: aggregate, report, deliver, finish
 * </pre>
 * Every arrow is a continuation, and a continuation starts on the next poll (5 s minimum on SQL storage), so per-step
 * pays about one poll interval per hop. Grouped pays it twice, and Pro persists each runStepOnce the moment it finishes.
 * <p>
 * {@code runAfterSuccessOf} is the JobBuilder form of {@code continueWith}; the builder lets every job in the chain
 * get a deterministic id. Pro's transaction plugin saves the jobs in the same transaction as the LAUNCHED state,
 * so a crash leaves either the whole chain or nothing.
 */
@Component
class ProChainLauncher implements ChainLauncher {

    static final String POSITIONS_MUTEX = "positions-dataset";
    static final String DATABASE_HEAVY = "database-heavy";

    private final JobScheduler jobScheduler;
    private final ChainRunRepository runs;
    private final TransactionTemplate transaction;
    private final String chainShape;

    ProChainLauncher(JobScheduler jobScheduler, ChainRunRepository runs, TransactionTemplate transaction,
                     @Value("${nightly-close.pro.chain-shape:per-step}") String chainShape) {
        this.jobScheduler = jobScheduler;
        this.runs = runs;
        this.transaction = transaction;
        this.chainShape = chainShape;
    }

    @Override
    public void launch(ChainRun run) {
        Stream<JobBuilder> chain = switch (chainShape) {
            case "per-step" -> perStep(run);
            case "grouped" -> grouped(run);
            default -> throw new IllegalStateException("Unknown nightly-close.pro.chain-shape " + chainShape);
        };
        transaction.executeWithoutResult(status -> {
            jobScheduler.create(chain);
            runs.advance(run.runId(), RunState.SUBMITTED, RunState.LAUNCHED);
        });
    }

    private Stream<JobBuilder> perStep(ChainRun run) {
        UUID runId = run.runId();
        String name = "Nightly close " + run.businessDate() + " run " + run.runNumber() + ": ";
        UUID watcher = jobId(runId, "watcher");
        UUID extract = jobId(runId, "extract");
        UUID scoring = jobId(runId, "scoring");
        UUID aggregate = jobId(runId, "aggregate");
        UUID report = jobId(runId, "report");
        UUID deliver = jobId(runId, "deliver");

        return Stream.of(
                aJob().withId(watcher).withName(name + "1 watcher")
                        .<ProChainJobs>withJobLambda(jobs -> jobs.watch(runId)),
                aJob().withId(extract).withName(name + "2 extract").runAfterSuccessOf(watcher)
                        .withMutex(POSITIONS_MUTEX)
                        .<ProChainJobs>withJobLambda(jobs -> jobs.extract(runId)),
                aBatchJob().withId(scoring).withName(name + "3 risk scoring per portfolio").runAfterSuccessOf(extract)
                        .<ProChainJobs>withJobLambda(jobs -> jobs.fanOut(runId)),
                aJob().withId(aggregate).withName(name + "4 aggregate").runAfterSuccessOf(scoring)
                        .withRateLimiter(DATABASE_HEAVY)
                        .<ProChainJobs>withJobLambda(jobs -> jobs.aggregate(runId)),
                aJob().withId(report).withName(name + "5 regulatory report").runAfterSuccessOf(aggregate)
                        .<ProChainJobs>withJobLambda(jobs -> jobs.report(runId)),
                aJob().withId(deliver).withName(name + "6 deliver").runAfterSuccessOf(report)
                        .<ProChainJobs>withJobLambda(jobs -> jobs.deliver(runId)),
                aJob().withId(jobId(runId, "finish")).withName(name + "finish").runAfterSuccessOf(deliver)
                        .<ProChainJobs>withJobLambda(jobs -> jobs.finish(runId)));
    }

    private Stream<JobBuilder> grouped(ChainRun run) {
        UUID runId = run.runId();
        String name = "Nightly close " + run.businessDate() + " run " + run.runNumber() + ": ";
        UUID head = jobId(runId, "head");
        UUID scoring = jobId(runId, "scoring");
        return Stream.of(
                aJob().withId(head).withName(name + "watcher, extract")
                        .withMutex(POSITIONS_MUTEX)
                        .<ProChainJobs>withJobLambda(jobs -> jobs.head(runId, JobContext.Null)),
                aBatchJob().withId(scoring).withName(name + "risk scoring per portfolio").runAfterSuccessOf(head)
                        .<ProChainJobs>withJobLambda(jobs -> jobs.fanOut(runId)),
                aJob().withId(jobId(runId, "tail")).withName(name + "aggregate, report, deliver").runAfterSuccessOf(scoring)
                        .<ProChainJobs>withJobLambda(jobs -> jobs.tail(runId, JobContext.Null)));
    }
}
