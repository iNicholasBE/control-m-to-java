package org.jobrunr.examples.nightlyclose.oss;

import org.jobrunr.scheduling.cron.CronExpression;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Why the nightly trigger's cron runs in UTC. A nightly job at 02:30 Brussels time, around the two DST changes:
 * <ul>
 *     <li>a naive wall-clock scheduler (fire whenever the local clock reads 02:30) fires twice on 25 October 2026 and
 *     not at all on 28 March 2027;</li>
 *     <li>JobRunr's cron in a local zone fires once per day, but the spring run moves to 03:30 local and the gaps
 *     between runs become 25 and 23 hours;</li>
 *     <li>the same cron in UTC fires every 24 hours, always at the same instant.</li>
 * </ul>
 * Writes the table to {@code results/jobrunr/dst.md} when that folder exists.
 */
class DstTest {

    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");
    private static final String CRON = "30 2 * * *";
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Map<String, List<Fire>> TABLES = new TreeMap<>();

    record Fire(String scheduler, Instant at, ZoneId zone, Duration sincePrevious) {
    }

    @Test
    void autumnFallBack() {
        Instant from = Instant.parse("2026-10-23T12:00:00Z");
        Instant to = Instant.parse("2026-10-27T12:00:00Z");

        List<Fire> naive = naiveWallClock(from, to);
        List<Fire> local = jobRunrCron(BRUSSELS, from, to);
        List<Fire> utc = jobRunrCron(ZoneOffset.UTC, from, to);
        TABLES.put("1. Fall back, 2026-10-25 (03:00 CEST becomes 02:00 CET)", concat(naive, local, utc));

        assertThat(firesOn(naive, LocalDate.parse("2026-10-25"))).as("naive local scheduler double-fires").isEqualTo(2);
        assertThat(firesOn(local, LocalDate.parse("2026-10-25"))).as("JobRunr local cron fires once").isEqualTo(1);
        assertThat(local).extracting(Fire::sincePrevious).contains(Duration.ofHours(25));
        assertThat(utc).extracting(Fire::sincePrevious).containsOnly(null, Duration.ofHours(24));
    }

    @Test
    void springForward() {
        Instant from = Instant.parse("2027-03-25T12:00:00Z");
        Instant to = Instant.parse("2027-03-30T12:00:00Z");

        List<Fire> naive = naiveWallClock(from, to);
        List<Fire> local = jobRunrCron(BRUSSELS, from, to);
        List<Fire> utc = jobRunrCron(ZoneOffset.UTC, from, to);
        TABLES.put("2. Spring forward, 2027-03-28 (02:00 CET becomes 03:00 CEST)", concat(naive, local, utc));

        assertThat(firesOn(naive, LocalDate.parse("2027-03-28"))).as("naive local scheduler skips").isZero();
        assertThat(local).filteredOn(f -> f.at().atZone(BRUSSELS).toLocalDate().equals(LocalDate.parse("2027-03-28")))
                .singleElement()
                .satisfies(f -> assertThat(f.at().atZone(BRUSSELS).toLocalTime()).isEqualTo(LocalTime.of(3, 30)));
        assertThat(local).extracting(Fire::sincePrevious).contains(Duration.ofHours(23));
        assertThat(utc).extracting(Fire::sincePrevious).containsOnly(null, Duration.ofHours(24));
    }

    /** Chains next() like the recurring job does: each fire is computed from the previous one. */
    private static List<Fire> jobRunrCron(ZoneId zone, Instant from, Instant to) {
        var cron = new CronExpression(CRON);
        var fires = new ArrayList<Fire>();
        Instant previous = null;
        for (Instant next = cron.next(CREATED, from, zone); next.isBefore(to); next = cron.next(CREATED, next, zone)) {
            String name = zone.equals(ZoneOffset.UTC) ? "JobRunr cron, zoneId UTC" : "JobRunr cron, zoneId Europe/Brussels";
            fires.add(new Fire(name, next, BRUSSELS, previous == null ? null : Duration.between(previous, next)));
            previous = next;
        }
        return fires;
    }

    /** A scheduler that ticks every minute and fires when the local wall clock reads 02:30. */
    private static List<Fire> naiveWallClock(Instant from, Instant to) {
        var fires = new ArrayList<Fire>();
        Instant previous = null;
        for (Instant tick = from.truncatedTo(ChronoUnit.MINUTES); tick.isBefore(to); tick = tick.plus(Duration.ofMinutes(1))) {
            if (tick.atZone(BRUSSELS).toLocalTime().equals(LocalTime.of(2, 30))) {
                fires.add(new Fire("Naive wall clock, Europe/Brussels", tick, BRUSSELS, previous == null ? null : Duration.between(previous, tick)));
                previous = tick;
            }
        }
        return fires;
    }

    private static long firesOn(List<Fire> fires, LocalDate localDate) {
        return fires.stream().filter(f -> f.at().atZone(BRUSSELS).toLocalDate().equals(localDate)).count();
    }

    @SafeVarargs
    private static List<Fire> concat(List<Fire>... lists) {
        var all = new ArrayList<Fire>();
        for (List<Fire> list : lists) all.addAll(list);
        return all;
    }

    @AfterAll
    static void writeReport() throws IOException {
        var md = new StringBuilder("""
                # DST: a 02:30 nightly cron around the two clock changes

                Generated by `DstTest` in `nightly-close-jobrunr/oss` (JobRunr %s `CronExpression`, cron `%s`).
                Local times are Europe/Brussels. "Since previous" is the wall-clock gap between two runs.

                """.formatted(CronExpression.class.getPackage().getImplementationVersion() == null ? "9.0.2" : CronExpression.class.getPackage().getImplementationVersion(), CRON));
        TABLES.forEach((title, fires) -> {
            md.append("## ").append(title).append("\n\n| Scheduler | Fires at (UTC) | Local time | Since previous |\n|---|---|---|---|\n");
            for (Fire fire : fires) {
                md.append("| ").append(fire.scheduler()).append(" | ").append(fire.at())
                        .append(" | ").append(fire.at().atZone(BRUSSELS).toLocalDateTime().toString().replace('T', ' '))
                        .append(" | ").append(fire.sincePrevious() == null ? "" : fire.sincePrevious().toHours() + " h").append(" |\n");
            }
            md.append('\n');
        });
        md.append("""
                ## Reading it

                - A scheduler that fires on the local wall clock runs the close **twice** on 25 October and **not at all** on 28 March.
                - JobRunr's cron with a local `zoneId` does neither: it fires once on both nights. The cost is subtler. In spring the
                  run moves to 03:30 local (01:30 UTC, an hour later than "02:30" suggests), and the gaps between runs become 25 and
                  23 hours. A downstream system that expects the close at a fixed UTC time sees it move twice a year.
                - The same cron with `zoneId = "UTC"` fires every 24 hours at the same instant. The nightly trigger in this repository
                  uses UTC and derives the business date from the fire time, once.
                """);
        Path report = Path.of(System.getProperty("dst.report", "../../results/jobrunr/dst.md"));
        if (Files.isDirectory(report.toAbsolutePath().getParent())) Files.writeString(report, md);
    }
}
