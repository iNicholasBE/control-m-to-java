package io.example.nightlyclose.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.quartz.CronExpression;
import org.quartz.impl.calendar.HolidayCalendar;

class BusinessCalendarTest {

    final HolidayCalendar calendar = BusinessCalendar.load(Path.of("holidays.txt"));
    final CronExpression cron = utcCron("0 30 22 * * ?");

    static CronExpression utcCron(String expression) {
        try {
            CronExpression cron = new CronExpression(expression);
            cron.setTimeZone(TimeZone.getTimeZone("UTC"));
            return cron;
        } catch (java.text.ParseException e) {
            throw new IllegalArgumentException(e);
        }
    }

    LocalDate businessDate(String scheduledFireTime) {
        return BusinessCalendar.businessDateFor(calendar, cron, Instant.parse(scheduledFireTime));
    }

    @Test
    void onTimeFireBelongsToItsOwnDay() {
        assertThat(businessDate("2026-10-22T22:30:00Z")).isEqualTo(LocalDate.parse("2026-10-22")); // Thursday
    }

    @Test
    void fireAndProceedRecoveryNextMorningBelongsToTheMissedNight() {
        // Thursday 22:30 missed, first node back on Friday 07:00: FireAndProceed reports Friday 07:00
        assertThat(businessDate("2026-10-23T07:00:00Z")).isEqualTo(LocalDate.parse("2026-10-22"));
    }

    @Test
    void weekendRecoveryBelongsToFriday() {
        assertThat(businessDate("2026-10-24T07:00:00Z")).isEqualTo(LocalDate.parse("2026-10-23"));
        assertThat(businessDate("2026-10-26T07:00:00Z")).isEqualTo(LocalDate.parse("2026-10-23"));
    }

    @Test
    void holidaysAreNotBusinessDays() {
        assertThat(BusinessCalendar.isBusinessDay(calendar, LocalDate.parse("2026-11-11"))).isFalse(); // Wednesday
        assertThat(BusinessCalendar.isBusinessDay(calendar, LocalDate.parse("2026-11-10"))).isTrue();
        assertThat(businessDate("2026-11-12T07:00:00Z")).isEqualTo(LocalDate.parse("2026-11-10"));
        assertThat(calendar.getNextIncludedTime(Instant.parse("2026-11-11T12:00:00Z").toEpochMilli()))
                .isEqualTo(Instant.parse("2026-11-12T00:00:00Z").toEpochMilli());
        assertThat(calendar.getNextIncludedTime(Instant.parse("2026-12-25T12:00:00Z").toEpochMilli()))
                .isEqualTo(Instant.parse("2026-12-28T00:00:00Z").toEpochMilli()); // Christmas Friday, then the weekend
    }
}
