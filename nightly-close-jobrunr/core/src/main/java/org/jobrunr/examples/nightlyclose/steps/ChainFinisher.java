package org.jobrunr.examples.nightlyclose.steps;

import org.jobrunr.examples.nightlyclose.NightlyCloseProperties;
import org.jobrunr.examples.nightlyclose.contract.ChainRun;
import org.jobrunr.examples.nightlyclose.contract.ChainRunRepository;
import org.jobrunr.examples.nightlyclose.contract.ChainStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.UUID;

/**
 * Closes a run: state DONE, the bridge's DONE file, and the SLA check that Control-M did with its 06:00 SLA.
 * Safe to run twice.
 */
@Component
public class ChainFinisher {

    private static final Logger LOG = LoggerFactory.getLogger(ChainFinisher.class);

    private final ChainRunRepository runs;
    private final ChainStatusService status;
    private final NightlyCloseProperties properties;

    public ChainFinisher(ChainRunRepository runs, ChainStatusService status, NightlyCloseProperties properties) {
        this.runs = runs;
        this.status = status;
        this.properties = properties;
    }

    public void finish(UUID runId) throws IOException {
        runs.markDone(runId);
        ChainRun run = runs.get(runId);
        writeDoneFile(run);

        var deadline = status.slaDeadline(run.businessDate());
        var lateness = Duration.between(deadline, run.finishedAt());
        if (lateness.isPositive()) {
            LOG.warn("SLA MISSED: nightly close {} run {} finished {} after the {} {} deadline",
                    run.businessDate(), run.runNumber(), lateness, properties.slaTime(), properties.slaZone());
        } else {
            LOG.info("SLA met: nightly close {} run {} finished {} before the deadline", run.businessDate(), run.runNumber(), lateness.negated());
        }
    }

    /**
     * The bridge's completion signal. Also written again when a trigger arrives for a run that is already done:
     * the mainframe re-sends READY when it never saw DONE, and it must get its answer the same way.
     */
    public void writeDoneFile(ChainRun run) throws IOException {
        Files.createDirectories(properties.bridgeOutboxDir());
        Path done = properties.bridgeOutboxDir().resolve("NIGHTLY-CLOSE-" + run.businessDate() + "-DONE");
        Path partial = done.resolveSibling(done.getFileName() + ".part");
        Files.writeString(partial, """
                runId=%s
                runNumber=%d
                reportSha256=%s
                deliveryReceipt=%s
                """.formatted(run.runId(), run.runNumber(), run.reportSha256(), run.deliveryReceipt()));
        Files.move(partial, done, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
