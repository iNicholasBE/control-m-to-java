package io.example.nightlyclose.batch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import io.example.nightlyclose.ChainProperties;
import io.example.nightlyclose.RunKey;
import io.example.nightlyclose.chain.ChainRuns;
import io.example.nightlyclose.chain.InputFiles;

/**
 * Control-M file watcher. Each execute() call is one poll and runs in its own short transaction: returning
 * CONTINUABLE instead of sleeping in a loop keeps the step stoppable and avoids one transaction open for hours.
 */
@Component
public class WatcherTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(WatcherTasklet.class);

    private final ChainProperties properties;
    private final InputFiles inputFiles;
    private final ChainRuns chainRuns;

    public WatcherTasklet(ChainProperties properties, InputFiles inputFiles, ChainRuns chainRuns) {
        this.properties = properties;
        this.inputFiles = inputFiles;
        this.chainRuns = chainRuns;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        RunKey key = RunKey.of(chunkContext.getStepContext().getStepExecution().getJobParameters());
        Path file = inputFiles.positions(key.businessDate());
        if (Files.isRegularFile(file) && sizeIsStable(file)) {
            String hash = InputFiles.sha256(file);
            String onRecord = chainRuns.recordInputHash(key, hash);
            if (!hash.equals(onRecord)) {
                throw new IllegalStateException("Input " + file + " has hash " + hash + " but " + key.runId()
                        + " was registered with " + onRecord + "; start a new runNumber");
            }
            log.info("{}: found {} ({} bytes, sha256 {})", key.runId(), file, Files.size(file), hash);
            return RepeatStatus.FINISHED;
        }
        Instant started = chunkContext.getStepContext().getStepExecution().getStartTime()
                .atZone(java.time.ZoneOffset.UTC).toInstant();
        if (Duration.between(started, Instant.now()).compareTo(properties.watcher().timeout()) > 0) {
            throw new IllegalStateException("Timed out waiting for " + file);
        }
        Thread.sleep(properties.watcher().pollInterval());
        return RepeatStatus.CONTINUABLE;
    }

    /** A producer that copies instead of renaming may still be writing; wait until the size stops changing. */
    private boolean sizeIsStable(Path file) throws Exception {
        long before = Files.size(file);
        Thread.sleep(200);
        return Files.size(file) == before && before > 0;
    }
}
