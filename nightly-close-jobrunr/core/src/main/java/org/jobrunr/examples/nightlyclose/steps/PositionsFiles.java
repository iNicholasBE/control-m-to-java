package org.jobrunr.examples.nightlyclose.steps;

import org.jobrunr.examples.nightlyclose.NightlyCloseProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The inbox of {@code POSITIONS-<businessDate>.csv} files. */
@Component
public class PositionsFiles {

    private static final Pattern NAME = Pattern.compile("POSITIONS-(\\d{4}-\\d{2}-\\d{2})\\.csv");
    /** A file whose size did not change for this long is complete, like a Control-M file watcher's minimum age. */
    private static final Duration STABLE_FOR = Duration.ofSeconds(1);

    private final NightlyCloseProperties properties;

    public PositionsFiles(NightlyCloseProperties properties) {
        this.properties = properties;
    }

    public Path pathFor(LocalDate businessDate) {
        return properties.inboxDir().resolve("POSITIONS-" + businessDate + ".csv");
    }

    /** Business dates that have a positions file in the inbox, complete or not. */
    public List<LocalDate> datesInInbox() throws IOException {
        if (!Files.isDirectory(properties.inboxDir())) return List.of();
        try (var files = Files.list(properties.inboxDir())) {
            return files.map(f -> NAME.matcher(f.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(m -> LocalDate.parse(m.group(1)))
                    .sorted()
                    .toList();
        }
    }

    public boolean isComplete(LocalDate businessDate) {
        Path file = pathFor(businessDate);
        try {
            return Files.exists(file) && Files.getLastModifiedTime(file).toInstant().isBefore(Instant.now().minus(STABLE_FOR));
        } catch (IOException e) {
            return false;
        }
    }

    public Optional<String> sha256IfPresent(LocalDate businessDate) {
        Path file = pathFor(businessDate);
        return Files.exists(file) ? Optional.of(sha256(file)) : Optional.empty();
    }

    public static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int read; (read = in.read(buffer)) > 0; ) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
