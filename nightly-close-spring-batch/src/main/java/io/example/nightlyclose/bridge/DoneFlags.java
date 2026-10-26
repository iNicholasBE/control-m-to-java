package io.example.nightlyclose.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.example.nightlyclose.ChainProperties;
import io.example.nightlyclose.RunKey;

/** Writes bridge/outbox/NIGHTLY-CLOSE-&lt;businessDate&gt;-DONE for the mainframe side of the bridge. */
@Component
public class DoneFlags {

    private static final Logger log = LoggerFactory.getLogger(DoneFlags.class);

    private final ChainProperties properties;

    public DoneFlags(ChainProperties properties) {
        this.properties = properties;
    }

    public void write(RunKey key) {
        try {
            Path outbox = properties.bridge().outbox();
            Files.createDirectories(outbox);
            Path temp = outbox.resolve("NIGHTLY-CLOSE-" + key.businessDate() + "-DONE.tmp");
            Files.writeString(temp, key.runId() + "\n");
            Files.move(temp, outbox.resolve("NIGHTLY-CLOSE-" + key.businessDate() + "-DONE"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("{}: could not write bridge DONE file", key.runId(), e);
        }
    }
}
