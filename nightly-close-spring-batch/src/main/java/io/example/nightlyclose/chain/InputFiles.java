package io.example.nightlyclose.chain;

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

import org.springframework.stereotype.Component;

import io.example.nightlyclose.ChainProperties;

@Component
public class InputFiles {

    private final ChainProperties properties;

    public InputFiles(ChainProperties properties) {
        this.properties = properties;
    }

    public Path positions(LocalDate businessDate) {
        return properties.inbox().resolve("POSITIONS-" + businessDate + ".csv");
    }

    public Optional<String> hashIfPresent(LocalDate businessDate) {
        Path file = positions(businessDate);
        return Files.isRegularFile(file) ? Optional.of(sha256(file)) : Optional.empty();
    }

    public static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 20];
            for (int read; (read = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
