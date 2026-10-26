package org.jobrunr.examples.nightlyclose.contract;

import org.jobrunr.examples.nightlyclose.ChainIds;
import org.jobrunr.examples.nightlyclose.calendar.BusinessCalendar;
import org.jobrunr.examples.nightlyclose.steps.PositionsFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The enqueue contract, shared by the REST endpoint, the bridge file watcher and the recurring trigger.
 * <ul>
 *     <li>The run id is derived from {@code nightly-close|<businessDate>|<runNumber>}.</li>
 *     <li>Same id, same parameters: nothing new starts, the existing run is returned.</li>
 *     <li>Same id, different parameters (a different input file): conflict, the existing run is returned with a reason.</li>
 *     <li>A new run number is an explicit rerun and gets a new id.</li>
 * </ul>
 */
@Service
public class EnqueueContract {

    private static final Logger LOG = LoggerFactory.getLogger(EnqueueContract.class);

    private final ChainRunRepository runs;
    private final ChainLauncher launcher;
    private final PositionsFiles positionsFiles;
    private final BusinessCalendar calendar;

    public EnqueueContract(ChainRunRepository runs, ChainLauncher launcher, PositionsFiles positionsFiles, BusinessCalendar calendar) {
        this.runs = runs;
        this.launcher = launcher;
        this.positionsFiles = positionsFiles;
        this.calendar = calendar;
    }

    public Submission submit(LocalDate businessDate, int runNumber) {
        if (runNumber < 1) return Submission.rejected("runNumber must be 1 or higher");
        if (!calendar.isBusinessDay(businessDate)) return Submission.rejected(businessDate + " is not a business day");

        var runId = ChainIds.runId(businessDate, runNumber);
        // Null when the positions file has not arrived yet; the watcher step records it later.
        String inputSha256 = positionsFiles.sha256IfPresent(businessDate).orElse(null);

        boolean created = runs.insertIfAbsent(runId, businessDate, runNumber, inputSha256);
        ChainRun run = runs.get(runId);

        if (!created && run.inputSha256() != null && inputSha256 != null && !Objects.equals(run.inputSha256(), inputSha256)) {
            return Submission.conflict(run, "run " + runNumber + " for " + businessDate + " was submitted with input sha256 "
                    + run.inputSha256() + ", the positions file now has sha256 " + inputSha256 + ". Submit runNumber "
                    + (runNumber + 1) + " for an explicit rerun.");
        }
        // Launching again is safe while the run is still SUBMITTED: the job ids are deterministic and the
        // scheduler ignores ids it already has. It covers a crash between inserting the row and creating the jobs.
        if (run.state() == RunState.SUBMITTED) {
            launcher.launch(run);
            run = runs.get(runId);
        }
        LOG.info("Enqueue contract: {} run {} for {} ({})", created ? "created" : "existing", runNumber, businessDate, runId);
        return created ? Submission.created(run) : Submission.existing(run);
    }

    public record Submission(Outcome outcome, ChainRun run, String reason) {

        public enum Outcome {CREATED, EXISTING, CONFLICT, REJECTED}

        static Submission created(ChainRun run) {
            return new Submission(Outcome.CREATED, run, null);
        }

        static Submission existing(ChainRun run) {
            return new Submission(Outcome.EXISTING, run, null);
        }

        static Submission conflict(ChainRun run, String reason) {
            return new Submission(Outcome.CONFLICT, run, reason);
        }

        static Submission rejected(String reason) {
            return new Submission(Outcome.REJECTED, null, reason);
        }
    }
}
