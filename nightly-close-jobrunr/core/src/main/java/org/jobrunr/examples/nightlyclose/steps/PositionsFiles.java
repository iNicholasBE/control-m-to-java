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
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;

@Component
public class PositionsFiles {

    private final NightlyCloseProperties properties;

    public PositionsFiles(NightlyCloseProperties properties) {
        this.properties = properties;
    }

    public Path pathFor(LocalDate businessDate) {
        return properties.inboxDir().resolve("POSITIONS-" + businessDate + ".csv");
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
