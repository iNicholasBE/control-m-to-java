package org.jobrunr.examples.nightlyclose.calendar;

import org.jobrunr.examples.nightlyclose.NightlyCloseProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Monday to Friday minus the dates in {@code holidays.txt} (one ISO date per line, {@code #} starts a comment).
 * The Control-M equivalent is a working-days calendar combined with a holiday calendar.
 */
@Component
public class BusinessCalendar {

    private final Path holidaysFile;

    public BusinessCalendar(NightlyCloseProperties properties) {
        this.holidaysFile = properties.holidaysFile();
    }

    public boolean isBusinessDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY
                && !holidays().contains(date);
    }

    public LocalDate previousBusinessDay(LocalDate date) {
        LocalDate candidate = date.minusDays(1);
        while (!isBusinessDay(candidate)) candidate = candidate.minusDays(1);
        return candidate;
    }

    /** Read on every call: operations edit the holiday file without restarting the scheduler. */
    private Set<LocalDate> holidays() {
        if (!Files.exists(holidaysFile)) return Set.of();
        try (var lines = Files.lines(holidaysFile)) {
            return lines.map(line -> line.replaceAll("#.*", "").strip())
                    .filter(line -> !line.isEmpty())
                    .map(LocalDate::parse)
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
