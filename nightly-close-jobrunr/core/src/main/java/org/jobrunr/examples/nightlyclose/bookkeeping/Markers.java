package org.jobrunr.examples.nightlyclose.bookkeeping;

import org.jobrunr.examples.nightlyclose.NightlyCloseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * Writes {@code <markers-dir>/<name>.entered} so the kill-test harness knows where the JVM is.
 * When {@code nightly-close.test.pause-at} names this marker, the thread sleeps right after writing it,
 * so a {@code kill -9} from the harness lands at that exact point instead of somewhere after it.
 */
@Component
public class Markers {

    private static final Logger LOG = LoggerFactory.getLogger(Markers.class);

    private final NightlyCloseProperties properties;

    public Markers(NightlyCloseProperties properties) {
        this.properties = properties;
    }

    public void enter(String name) {
        try {
            Files.createDirectories(properties.markersDir());
            Files.writeString(properties.markersDir().resolve(name + ".entered"), "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (name.equals(properties.test().pauseAt())) {
            LOG.warn("Test hook: pausing {} at marker '{}'", properties.test().pauseDuration(), name);
            try {
                Thread.sleep(properties.test().pauseDuration());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
