package io.example.nightlyclose.batch;

import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

import io.example.nightlyclose.RunKey;
import io.example.nightlyclose.chain.ChainRuns;
import io.example.nightlyclose.chain.InputFiles;

/**
 * A restart resumes the reader at line N of whatever file is there now. If someone replaced the file in between,
 * line N means something else: refuse instead of silently mixing two files in one run.
 */
@Component
public class ExtractInputGuard implements StepExecutionListener {

    private final InputFiles inputFiles;
    private final ChainRuns chainRuns;

    public ExtractInputGuard(InputFiles inputFiles, ChainRuns chainRuns) {
        this.inputFiles = inputFiles;
        this.chainRuns = chainRuns;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        RunKey key = RunKey.of(stepExecution.getJobParameters());
        String onRecord = chainRuns.find(key).map(ChainRuns.ChainRun::inputHash).orElse(null);
        String now = InputFiles.sha256(inputFiles.positions(key.businessDate()));
        if (onRecord != null && !onRecord.equals(now)) {
            throw new IllegalStateException(key.runId() + ": input file changed since the watcher saw it; start a new runNumber");
        }
    }
}
