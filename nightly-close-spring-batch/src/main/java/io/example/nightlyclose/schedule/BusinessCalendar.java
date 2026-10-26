package io.example.nightlyclose.schedule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import org.quartz.CronExpression;
import org.quartz.impl.calendar.HolidayCalendar;
import org.quartz.impl.calendar.WeeklyCalendar;

/**
 * The Control-M calendar "business days": Monday to Friday minus the dates in holidays.txt, as a Quartz
 * HolidayCalendar layered on a WeeklyCalendar that excludes the weekend. Everything in UTC.
 */
public final class BusinessCalendar {

    private BusinessCalendar() {
    }

    public static HolidayCalendar load(Path holidaysFile) {
        WeeklyCalendar weekdays = new WeeklyCalendar();
        weekdays.setTimeZone(TimeZone.getTimeZone("UTC"));
        // WeeklyCalendar excludes Sat+Sun by default; set explicitly so the reader does not have to know that.
        weekdays.setDayExcluded(java.util.Calendar.SATURDAY, true);
        weekdays.setDayExcluded(java.util.Calendar.SUNDAY, true);

        HolidayCalendar calendar = new HolidayCalendar(weekdays);
        calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
        calendar.setDescription("Business days: Mon-Fri minus " + holidaysFile);
        for (LocalDate holiday : readHolidays(holidaysFile)) {
            calendar.addExcludedDate(Date.from(holiday.atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        return calendar;
    }

    static List<LocalDate> readHolidays(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> LocalDate.parse(line.split("\\s+")[0]))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static boolean isBusinessDay(org.quartz.Calendar calendar, LocalDate date) {
        return calendar.isTimeIncluded(date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli());
    }

    /**
     * The business date a fire belongs to: the last day whose cron fire time is at or before the scheduled fire
     * time and which is a business day. Needed because a misfire handled with FireAndProceed reports the
     * recovery moment as its scheduled fire time: a Thursday 22:30 fire recovered on Friday 07:00 is Thursday's.
     */
    public static LocalDate businessDateFor(org.quartz.Calendar calendar, CronExpression cron, Instant scheduledFireTime) {
        LocalDate date = scheduledFireTime.atZone(ZoneOffset.UTC).toLocalDate();
        for (int i = 0; i < 31; i++, date = date.minusDays(1)) {
            Date dayStart = Date.from(date.atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1));
            Date fireOnThatDay = cron.getNextValidTimeAfter(dayStart);
            boolean firedOnThatDay = fireOnThatDay != null
                    && fireOnThatDay.toInstant().atZone(ZoneOffset.UTC).toLocalDate().equals(date);
            if (firedOnThatDay && !fireOnThatDay.toInstant().isAfter(scheduledFireTime) && isBusinessDay(calendar, date)) {
                return date;
            }
        }
        throw new IllegalStateException("No business day in the 31 days before " + scheduledFireTime);
    }
}
