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
import java.util.List;
import java.util.UUID;

/**
 * The six steps of the nightly close, as the scheduler sees them. Every method takes only the run id: all other
 * parameters, the business date included, were fixed when the run was submitted and are read from {@code chain_runs}.
 * Each step is safe to run again: that is what a crash in the middle of one forces on you, whatever the scheduler.
 */
@Component
public class NightlyCloseSteps {

    private static final Logger LOG = LoggerFactory.getLogger(NightlyCloseSteps.class);

    private final ChainRunRepository runs;
    private final StepRecorder recorder;
    private final Markers markers;
    private final PositionsFileWatcher watcher;
    private final PositionsExtractor extractor;
    private final RiskScorer scorer;
    private final PortfolioAggregator aggregator;
    private final RegulatoryReportWriter reportWriter;
    private final RegulatorClient regulator;
    private final ChainFinisher finisher;

    public NightlyCloseSteps(ChainRunRepository runs, StepRecorder recorder, Markers markers, PositionsFileWatcher watcher,
                             PositionsExtractor extractor, RiskScorer scorer, PortfolioAggregator aggregator,
                             RegulatoryReportWriter reportWriter, RegulatorClient regulator, ChainFinisher finisher) {
        this.runs = runs;
        this.recorder = recorder;
        this.markers = markers;
        this.watcher = watcher;
        this.extractor = extractor;
        this.scorer = scorer;
        this.aggregator = aggregator;
        this.reportWriter = reportWriter;
        this.regulator = regulator;
        this.finisher = finisher;
    }

    /** Step 1: wait for the positions file and pin its hash to the run. */
    public void watch(UUID runId) throws Exception {
        recorder.run(runId, "watcher", () -> {
            ChainRun run = runs.get(runId);
            String sha256 = watcher.awaitPositionsFile(run);
            if (run.inputSha256() != null && !run.inputSha256().equals(sha256)) {
                throw new IllegalStateException("Positions file for " + run.businessDate() + " changed after run "
                        + run.runNumber() + " was submitted. Submit a new runNumber to rerun with the new file.");
            }
            runs.markStarted(runId, sha256);
        });
    }

    /** Step 2: load the file into staging and decide the partitions. */
    public void extract(UUID runId) throws Exception {
        recorder.run(runId, "extract", () -> {
            long rows = extractor.extract(runs.get(runId));
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

    /** Step 3, one partition. */
    public void score(UUID runId, Partition partition) throws Exception {
        recorder.run(runId, partition.stepName(), () -> scorer.score(runId, partition));
    }

    /** The join as a recorded step: {@code body} is whatever the scheduler needs to do to start the next step. */
    public void join(UUID runId, StepRecorder.StepBody body) throws Exception {
        recorder.run(runId, "join", body);
    }

    public boolean joinWhenAllPartitionsDone(UUID runId) {
        return runs.joinWhenAllPartitionsDone(runId);
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
}
