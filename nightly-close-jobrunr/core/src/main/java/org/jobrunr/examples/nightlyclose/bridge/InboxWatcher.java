package org.jobrunr.examples.nightlyclose.bridge;

import org.jobrunr.examples.nightlyclose.ChainIds;
import org.jobrunr.examples.nightlyclose.bookkeeping.StepRecorder;
import org.jobrunr.examples.nightlyclose.calendar.BusinessCalendar;
import org.jobrunr.examples.nightlyclose.contract.ChainRun;
import org.jobrunr.examples.nightlyclose.contract.ChainRunRepository;
import org.jobrunr.examples.nightlyclose.contract.EnqueueContract;
import org.jobrunr.examples.nightlyclose.contract.RunState;
import org.jobrunr.examples.nightlyclose.steps.PositionsFiles;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Step 1, the Control-M file watcher, as a recurring job instead of a step that blocks a worker while it waits.
 * Each tick it starts every run whose positions file has arrived, then handles the bridge's READY files:
 * a new file starts run 1, and a run that was submitted before its file arrived (by the cron trigger, a READY file
 * or a POST) is launched now. Everything goes through the enqueue contract, so a tick that repeats is harmless.
 */
@Component
public class InboxWatcher {

    private final BridgeFileWatcher bridge;
    private final PositionsFiles positionsFiles;
    private final BusinessCalendar calendar;
    private final ChainRunRepository runs;
    private final EnqueueContract contract;
    private final StepRecorder recorder;

    public InboxWatcher(BridgeFileWatcher bridge, PositionsFiles positionsFiles, BusinessCalendar calendar,
                        ChainRunRepository runs, EnqueueContract contract, StepRecorder recorder) {
        this.bridge = bridge;
        this.positionsFiles = positionsFiles;
        this.calendar = calendar;
        this.runs = runs;
        this.contract = contract;
        this.recorder = recorder;
    }

    public void scan() throws Exception {
        for (LocalDate businessDate : positionsFiles.datesInInbox()) {
            if (calendar.isBusinessDay(businessDate) && positionsFiles.isComplete(businessDate)) startRun1(businessDate);
        }
        for (ChainRun waiting : runs.findInState(RunState.SUBMITTED)) {
            if (positionsFiles.isComplete(waiting.businessDate())) start(waiting.businessDate(), waiting.runNumber());
        }
        // After the positions files: a READY file for a run the watcher just started is answered "existing".
        bridge.scanInbox();
    }

    private void startRun1(LocalDate businessDate) throws Exception {
        Optional<ChainRun> run = runs.find(ChainIds.runId(businessDate, 1));
        // A run that is already under way (or done) needs nothing from the watcher.
        if (run.isEmpty() || run.get().state() == RunState.SUBMITTED) start(businessDate, 1);
    }

    private void start(LocalDate businessDate, int runNumber) throws Exception {
        recorder.run(ChainIds.runId(businessDate, runNumber), "watcher", () -> contract.submit(businessDate, runNumber));
    }
}
