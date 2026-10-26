package org.jobrunr.examples.nightlyclose.bridge;

import org.jobrunr.examples.nightlyclose.NightlyCloseProperties;
import org.jobrunr.examples.nightlyclose.contract.EnqueueContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File transport of the Control-M bridge. Control-M (or the bridge simulator) drops
 * {@code NIGHTLY-CLOSE-<businessDate>-READY} in the bridge inbox; an optional {@code runNumber=N} line in the file
 * asks for an explicit rerun. Each file goes through the enqueue contract and is then moved to {@code accepted/}
 * or {@code rejected/}. A file dropped twice is harmless: the contract returns the existing run.
 */
@Component
public class BridgeFileWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(BridgeFileWatcher.class);
    private static final Pattern READY = Pattern.compile("NIGHTLY-CLOSE-(\\d{4}-\\d{2}-\\d{2})-READY");
    private static final Pattern RUN_NUMBER = Pattern.compile("(?m)^\\s*runNumber\\s*=\\s*(\\d+)\\s*$");

    private final EnqueueContract contract;
    private final NightlyCloseProperties properties;

    public BridgeFileWatcher(EnqueueContract contract, NightlyCloseProperties properties) {
        this.contract = contract;
        this.properties = properties;
    }

    public void scanInbox() throws IOException {
        Path inbox = properties.bridgeInboxDir();
        if (!Files.isDirectory(inbox)) return;
        List<Path> readyFiles;
        try (var files = Files.list(inbox)) {
            readyFiles = files.filter(f -> READY.matcher(f.getFileName().toString()).matches()).sorted().toList();
        }
        for (Path ready : readyFiles) handle(ready);
    }

    private void handle(Path ready) throws IOException {
        Matcher name = READY.matcher(ready.getFileName().toString());
        if (!name.matches()) return;
        LocalDate businessDate = LocalDate.parse(name.group(1));
        Matcher runNumber = RUN_NUMBER.matcher(Files.readString(ready));
        int run = runNumber.find() ? Integer.parseInt(runNumber.group(1)) : 1;

        var submission = contract.submit(businessDate, run);
        LOG.info("Bridge file {}: {}", ready.getFileName(), submission.outcome());
        switch (submission.outcome()) {
            case CREATED, EXISTING -> moveTo(ready, "accepted");
            case CONFLICT, REJECTED -> {
                Files.createDirectories(properties.bridgeOutboxDir());
                Files.writeString(properties.bridgeOutboxDir().resolve("NIGHTLY-CLOSE-" + businessDate + "-REJECTED"),
                        submission.outcome() + ": " + submission.reason() + "\n");
                moveTo(ready, "rejected");
            }
        }
    }

    private static void moveTo(Path file, String folder) throws IOException {
        Path target = file.resolveSibling(folder).resolve(file.getFileName() + "." + System.currentTimeMillis());
        Files.createDirectories(target.getParent());
        Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
    }
}
