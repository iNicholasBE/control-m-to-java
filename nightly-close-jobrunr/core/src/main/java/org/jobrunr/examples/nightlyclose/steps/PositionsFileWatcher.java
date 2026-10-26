package org.jobrunr.examples.nightlyclose.steps;

import org.jobrunr.examples.nightlyclose.NightlyCloseProperties;
import org.jobrunr.examples.nightlyclose.contract.ChainRun;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;

/**
 * Step 1, the Control-M file watcher: wait until {@code POSITIONS-<businessDate>.csv} is in the inbox and complete.
 */
@Component
public class PositionsFileWatcher {

    private static final Duration POLL = Duration.ofMillis(500);
    /** A file whose size did not change for this long is considered complete, like a Control-M file watcher's "min age". */
    private static final Duration STABLE_FOR = Duration.ofSeconds(1);

    private final PositionsFiles files;
    private final NightlyCloseProperties properties;

    public PositionsFileWatcher(PositionsFiles files, NightlyCloseProperties properties) {
        this.files = files;
        this.properties = properties;
    }

    /** @return the sha256 of the complete file */
    public String awaitPositionsFile(ChainRun run) throws IOException, InterruptedException, TimeoutException {
        Path file = files.pathFor(run.businessDate());
        Instant deadline = Instant.now().plus(properties.watcherTimeout());
        while (!isComplete(file)) {
            if (Instant.now().isAfter(deadline)) {
                throw new TimeoutException("No complete " + file + " within " + properties.watcherTimeout());
            }
            Thread.sleep(POLL);
        }
        return PositionsFiles.sha256(file);
    }

    private static boolean isComplete(Path file) throws IOException {
        if (!Files.exists(file)) return false;
        var lastModified = Files.getLastModifiedTime(file).toInstant();
        return lastModified.isBefore(Instant.now().minus(STABLE_FOR));
    }
}
