package io.example.nightlyclose.tracking;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import io.example.nightlyclose.ChainProperties;

/** Writes ./markers/&lt;name&gt;.entered so an outside harness knows exactly where to land a kill -9. */
@Component
public class Markers {

    private final Path directory;

    public Markers(ChainProperties properties) {
        this.directory = properties.markers();
    }

    public void enter(String name) {
        try {
            Files.createDirectories(directory);
            Path marker = directory.resolve(name + ".entered");
            if (Files.exists(marker)) {
                Files.setLastModifiedTime(marker, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            } else {
                Files.createFile(marker);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
