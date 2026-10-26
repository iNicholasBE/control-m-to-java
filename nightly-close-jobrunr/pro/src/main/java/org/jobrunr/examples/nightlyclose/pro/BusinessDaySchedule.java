package org.jobrunr.examples.nightlyclose.pro;

import org.jobrunr.scheduling.cron.CronExpression;
import org.jobrunr.scheduling.custom.CustomSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JobRunr Pro custom schedule: a cron expression that skips weekends and the dates in a holiday file, so the
 * recurring job is never even created on a day without a close. Definition: {@code BusinessDaySchedule(<cron>;<holidays file>)}.
 * Any Pro cron works as the base, e.g. {@code Cron.lastBusinessDayOfTheMonth(22, 0)} for a month-end chain.
 */
public class BusinessDaySchedule extends CustomSchedule {

    private static final Logger LOG = LoggerFactory.getLogger(BusinessDaySchedule.class);
    /** Safety net: a holiday file that blocks every day must not loop forever. */
    private static final int MAX_DAYS_AHEAD = 366;

    private final CronExpression cron;
    private final Path holidaysFile;

    public BusinessDaySchedule(String definition) {
        super(definition);
        String[] parts = definition.split(";", 2);
        this.cron = new CronExpression(parts[0].strip());
        this.holidaysFile = Path.of(parts.length > 1 ? parts[1].strip() : "holidays.txt");
    }

    public static String definition(String cron, Path holidaysFile) {
        return CustomSchedule.expressionFor(BusinessDaySchedule.class, cron + ";" + holidaysFile);
    }

    @Override
    public Instant next(Instant createdAtInstant, Instant currentInstant, ZoneId zoneId) {
        Set<LocalDate> holidays = holidays();
        Instant candidate = cron.next(createdAtInstant, currentInstant, zoneId);
        for (int i = 0; i < MAX_DAYS_AHEAD * 24 && !isBusinessDay(candidate.atZone(zoneId).toLocalDate(), holidays); i++) {
            candidate = cron.next(createdAtInstant, candidate, zoneId);
        }
        return candidate;
    }

    private static boolean isBusinessDay(LocalDate date, Set<LocalDate> holidays) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(date);
    }

    /** A CustomSchedule must never throw: an unreadable file means no holidays, and we log it. */
    private Set<LocalDate> holidays() {
        try (var lines = Files.lines(holidaysFile)) {
            return lines.map(line -> line.replaceAll("#.*", "").strip()).filter(line -> !line.isEmpty())
                    .map(LocalDate::parse).collect(Collectors.toSet());
        } catch (Exception e) {
            LOG.warn("Cannot read holiday file {}, scheduling on weekdays only: {}", holidaysFile, e.toString());
            return Set.of();
        }
    }
}
