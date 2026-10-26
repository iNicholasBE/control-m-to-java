package io.example.nightlyclose.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.quartz.CronScheduleBuilder;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerUtils;
import org.quartz.spi.OperableTrigger;

/**
 * Fire times of a local-time cron versus a UTC cron around the two DST switches in Europe/Brussels:
 * 2026-10-25 (03:00 CEST -> 02:00 CET, 02:00-03:00 happens twice) and 2027-03-28 (02:00 CET -> 03:00 CEST,
 * 02:00-03:00 does not exist).
 */
class QuartzDstTest {

    static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");

    static List<Instant> fireTimes(String cron, ZoneId zone, String from, String to) {
        OperableTrigger trigger = (OperableTrigger) TriggerBuilder.newTrigger()
                .startAt(Date.from(Instant.parse(from)))
                .withSchedule(CronScheduleBuilder.cronSchedule(cron).inTimeZone(TimeZone.getTimeZone(zone)))
                .build();
        return TriggerUtils.computeFireTimesBetween(trigger, null, Date.from(Instant.parse(from)), Date.from(Instant.parse(to)))
                .stream().map(Date::toInstant).toList();
    }

    static void print(String label, List<Instant> times) {
        System.out.println(label);
        times.forEach(t -> System.out.printf("  %s UTC = %s%n", t, ZonedDateTime.ofInstant(t, BRUSSELS)));
    }

    @Test
    void autumnLocalCronInTheRepeatedHourFiresOnceUtcCronFiresDaily() {
        List<Instant> local = fireTimes("0 30 2 * * ?", BRUSSELS, "2026-10-23T00:00:00Z", "2026-10-28T00:00:00Z");
        List<Instant> utc = fireTimes("0 30 2 * * ?", ZoneId.of("UTC"), "2026-10-23T00:00:00Z", "2026-10-28T00:00:00Z");
        print("02:30 Europe/Brussels, Oct 2026", local);
        print("02:30 UTC, Oct 2026", utc);
        // 02:30 happens twice on 2026-10-25; Quartz fires once, at the second one (02:30 CET = 01:30Z)
        assertThat(local).doesNotContain(Instant.parse("2026-10-25T00:30:00Z"));
        assertThat(local).contains(Instant.parse("2026-10-25T01:30:00Z"));
        assertThat(local).hasSize(5);
        // so the gap between the 24th and 25th fire is 25 hours, not 24
        assertThat(java.time.Duration.between(local.get(1), local.get(2))).isEqualTo(java.time.Duration.ofHours(25));
        assertThat(utc).hasSize(5);
    }

    @Test
    void springLocalCronInTheMissingHourSkipsTheDay() {
        List<Instant> local = fireTimes("0 30 2 * * ?", BRUSSELS, "2027-03-26T00:00:00Z", "2027-03-31T00:00:00Z");
        print("02:30 Europe/Brussels, Mar 2027", local);
        // 02:30 does not exist on 2027-03-28: Quartz does not fire that day at all (no shift to 03:30)
        assertThat(local).noneMatch(t -> ZonedDateTime.ofInstant(t, BRUSSELS).toLocalDate().toString().equals("2027-03-28"));
        assertThat(local).hasSize(4);
    }

    @Test
    void theChainsUtcCronMovesOnTheLocalClockButNeverDoublesOrSkips() {
        List<Instant> autumn = fireTimes("0 30 22 * * ?", ZoneId.of("UTC"), "2026-10-22T00:00:00Z", "2026-10-28T00:00:00Z");
        List<Instant> spring = fireTimes("0 30 22 * * ?", ZoneId.of("UTC"), "2027-03-25T00:00:00Z", "2027-03-31T00:00:00Z");
        print("chain cron 22:30 UTC, Oct 2026", autumn);
        print("chain cron 22:30 UTC, Mar 2027", spring);
        assertThat(autumn).hasSize(6);
        assertThat(spring).hasSize(6);
        List<Instant> localTwentyTwoThirty = fireTimes("0 30 22 * * ?", BRUSSELS, "2026-10-22T00:00:00Z", "2026-10-28T00:00:00Z");
        print("22:30 Europe/Brussels, Oct 2026", localTwentyTwoThirty);
        // a UTC fire at 22:30Z lands on the next local calendar day in summer (00:30 CEST) and on the same day in
        // winter (23:30 CET): derive the business date from the UTC date, never from the local one
        assertThat(ZonedDateTime.ofInstant(autumn.getFirst(), BRUSSELS).toLocalDate().toString()).isEqualTo("2026-10-23");
        assertThat(ZonedDateTime.ofInstant(autumn.getLast(), BRUSSELS).toLocalDate().toString()).isEqualTo("2026-10-27");
    }
}
