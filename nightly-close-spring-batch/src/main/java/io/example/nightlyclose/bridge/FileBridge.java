package io.example.nightlyclose.bridge;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.example.nightlyclose.ChainProperties;
import io.example.nightlyclose.RunKey;
import io.example.nightlyclose.chain.ChainService;

/**
 * File transport bridge from Control-M during migration: a READY flag file starts the chain through the same
 * contract as the REST call; ChainJobListener writes the DONE flag. Safe on several nodes sharing the directory,
 * because a duplicate submit of the same identity is answered with the existing run.
 */
@Component
@ConditionalOnProperty(name = "chain.bridge.enabled", havingValue = "true", matchIfMissing = true)
public class FileBridge {

    private static final Logger log = LoggerFactory.getLogger(FileBridge.class);
    private static final Pattern READY = Pattern.compile("NIGHTLY-CLOSE-(\\d{4}-\\d{2}-\\d{2})-READY");

    private final ChainProperties properties;
    private final ChainService chains;

    public FileBridge(ChainProperties properties, ChainService chains) {
        this.properties = properties;
        this.chains = chains;
    }

    @Scheduled(fixedDelay = 5000)
    public void poll() throws IOException {
        Path inbox = properties.bridge().inbox();
        Files.createDirectories(inbox);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(inbox, "NIGHTLY-CLOSE-*-READY")) {
            for (Path file : files) {
                Matcher matcher = READY.matcher(file.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                RunKey key = new RunKey(LocalDate.parse(matcher.group(1)), 1);
                ChainService.Submission submission = chains.submit(key, "bridge-file");
                log.info("Bridge: {} -> HTTP-equivalent {}", file.getFileName(), submission.httpStatus());
                Path processed = inbox.resolve("processed");
                Files.createDirectories(processed);
                try {
                    Files.move(file, processed.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.NoSuchFileException anotherNodeMovedIt) {
                    // fine: the other node submitted the same identity and got the same run
                }
            }
        }
    }
}
