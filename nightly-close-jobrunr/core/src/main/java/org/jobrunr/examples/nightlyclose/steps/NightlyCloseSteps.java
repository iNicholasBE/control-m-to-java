package org.jobrunr.examples.nightlyclose.steps;

import org.jobrunr.examples.nightlyclose.ChainIds;
import org.jobrunr.examples.nightlyclose.bookkeeping.Markers;
import org.jobrunr.examples.nightlyclose.bookkeeping.StepRecorder;
import org.jobrunr.examples.nightlyclose.contract.ChainRun;
import org.jobrunr.examples.nightlyclose.contract.ChainRunRepository;
import org.jobrunr.examples.nightlyclose.contract.RunState;
import org.jobrunr.examples.nightlyclose.regulator.RegulatorClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * The steps of the nightly close, as the scheduler sees them. Every method takes only the run id: all other
 * parameters, the business date included, were fixed when the run was submitted and are read from {@code chain_runs}.
 * Each step is safe to run again: that is what a crash in the middle of one forces on you, whatever the scheduler.
 * (Step 1, the file watcher, is {@code InboxWatcher}: it starts the chain instead of being part of it.)
 */
@Component
public class NightlyCloseSteps {

    private static final Logger LOG = LoggerFactory.getLogger(NightlyCloseSteps.class);
    /** The Control-M resource pool: at most this many partitions hold a database connection at once. */
    private static final int MAX_PARALLEL_PARTITIONS = 20;

    private final ChainRunRepository runs;
    private final StepRecorder recorder;
    private final Markers markers;
    private final PositionsExtractor extractor;
    private final RiskScorer scorer;
    private final PortfolioAggregator aggregator;
    private final RegulatoryReportWriter reportWriter;
    private final RegulatorClient regulator;
    private final ChainFinisher finisher;

    public NightlyCloseSteps(ChainRunRepository runs, StepRecorder recorder, Markers markers,
                             PositionsExtractor extractor, RiskScorer scorer, PortfolioAggregator aggregator,
                             RegulatoryReportWriter reportWriter, RegulatorClient regulator, ChainFinisher finisher) {
        this.runs = runs;
        this.recorder = recorder;
        this.markers = markers;
        this.extractor = extractor;
        this.scorer = scorer;
        this.aggregator = aggregator;
        this.reportWriter = reportWriter;
        this.regulator = regulator;
        this.finisher = finisher;
    }

    /** Step 2: load the file into staging and decide the partitions. Refuses a file that changed since submit. */
    public void extract(UUID runId) throws Exception {
        recorder.run(runId, "extract", () -> {
            ChainRun run = runs.get(runId);
            String sha256 = PositionsFiles.sha256(extractor.positionsFile(run));
            if (run.inputSha256() != null && !run.inputSha256().equals(sha256)) {
                throw new IllegalStateException("Positions file for " + run.businessDate() + " changed after run "
                        + run.runNumber() + " was submitted. Submit a new runNumber to rerun with the new file.");
            }
            runs.markStarted(runId, sha256);
            long rows = extractor.extract(run);
            int partitions = scorer.partitionsOf(runId).size();
            runs.setPartitionsTotal(runId, partitions);
            LOG.info("Extracted {} positions into {} partitions for run {}", rows, partitions, runId);
        });
    }

    public List<Partition> partitions(UUID runId) {
        return scorer.partitionsOf(runId);
    }

    public void startScoring(UUID runId) {
        runs.advance(runId, RunState.RUNNING, RunState.SCORING);
    }

    /** Step 3, one partition. Pro runs each partition as a child job of the batch. */
    public void score(UUID runId, Partition partition) throws Exception {
        recorder.run(runId, partition.stepName(), () -> scorer.score(runId, partition));
    }

    /**
     * Step 3, all partitions in this JVM, at most {@value #MAX_PARALLEL_PARTITIONS} at once on virtual threads.
     * Partitions that finished in an earlier attempt are skipped, so a retried step only scores what is missing.
     * Ends with the join: every partition is in, the run moves to JOINED.
     */
    public void scoreAll(UUID runId) throws Exception {
        startScoring(runId);
        Set<Integer> finished = scorer.finishedPartitions(runId);
        var pool = new Semaphore(MAX_PARALLEL_PARTITIONS);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (Partition partition : partitions(runId)) {
                if (finished.contains(partition.number())) continue;
                futures.add(executor.submit(() -> {
                    pool.acquire();
                    try {
                        score(runId, partition);
                    } finally {
                        pool.release();
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) awaitPartition(future);
        }
        joinIfAllPartitionsDone(runId);
    }

    /** Records the moment the last partition is in, for the status endpoint and the kill test's "join" marker. */
    public void joinIfAllPartitionsDone(UUID runId) throws Exception {
        if (runs.joinWhenAllPartitionsDone(runId)) join(runId);
    }

    private void join(UUID runId) throws Exception {
        recorder.run(runId, "join", () -> {
        });
    }

    /** Step 4. */
    public void aggregate(UUID runId) throws Exception {
        recorder.run(runId, "aggregate", () -> {
            runs.advance(runId, RunState.JOINED, RunState.AGGREGATING);
            aggregator.aggregate(runId);
        });
    }

    /** Step 5. */
    public void report(UUID runId) throws Exception {
        recorder.run(runId, "report", () -> {
            Path report = reportWriter.write(runs.get(runId));
            runs.setReport(runId, report.toString(), PositionsFiles.sha256(report));
        });
    }

    /**
     * Step 6: call the regulator, then record the receipt. A crash between the two makes the next attempt call
     * again; the idempotency key turns that second call into a rejected duplicate instead of a second filing.
     */
    public void deliver(UUID runId) throws Exception {
        recorder.run(runId, "deliver", () -> {
            ChainRun run = runs.get(runId);
            String key = ChainIds.idempotencyKey(run.businessDate(), run.runNumber());
            markers.enter("deliver-before-call");
            var receipt = regulator.submit(key, run.businessDate(), run.runNumber(), Path.of(run.reportFile()));
            markers.enter("deliver-after-call-before-record");
            if (receipt.duplicate()) LOG.warn("Regulator already had {}: receipt {}", key, receipt.receiptId());
            runs.setDeliveryReceipt(runId, receipt.receiptId());
        });
    }

    /** DONE state, DONE file for the bridge, SLA check. */
    public void finish(UUID runId) throws Exception {
        finisher.finish(runId);
    }

    private static void awaitPartition(Future<?> future) throws Exception {
        try {
            future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }
}
