package io.example.nightlyclose.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.quartz.CronScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.calendar.HolidayCalendar;

/**
 * What happens to the nightly cron trigger when no scheduler was up at its fire time. "Down across the fire time"
 * is simulated by scheduling a trigger whose first fire time already lies in the past and only then starting
 * the scheduler: that is the state a JDBC job store finds on boot, and the same updateAfterMisfire code runs.
 */
class QuartzMisfireTest {

    record Fire(Instant scheduled, Instant actual) {
    }

    static final List<Fire> FIRES = new CopyOnWriteArrayList<>();

    public static class RecordingJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
            FIRES.add(new Fire(context.getScheduledFireTime().toInstant(), context.getFireTime().toInstant()));
        }
    }

    private Scheduler scheduler;

    @AfterEach
    void shutdown() throws Exception {
        if (scheduler != null) {
            scheduler.shutdown(true);
        }
        FIRES.clear();
    }

    /** A daily UTC cron whose fire time today was two minutes ago; the trigger existed since ten minutes ago. */
    private Trigger missedDailyTrigger(CronScheduleBuilder schedule, Instant missedFire) {
        return TriggerBuilder.newTrigger().withIdentity("nightly-close-cron")
                .startAt(Date.from(missedFire.minus(Duration.ofMinutes(8))))
                .withSchedule(schedule.inTimeZone(TimeZone.getTimeZone("UTC")))
                .build();
    }

    private static String dailyCronAt(Instant time) {
        ZonedDateTime t = time.atZone(ZoneOffset.UTC);
        return t.getSecond() + " " + t.getMinute() + " " + t.getHour() + " * * ?";
    }

    private Scheduler newScheduler() throws Exception {
        Properties p = new Properties();
        p.setProperty("org.quartz.scheduler.instanceName", "misfire-test-" + UUID.randomUUID());
        p.setProperty("org.quartz.threadPool.threadCount", "1");
        p.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        p.setProperty("org.quartz.jobStore.misfireThreshold", "1000");
        scheduler = new StdSchedulerFactory(p).getScheduler();
        return scheduler;
    }

    private JobDetail job() {
        return JobBuilder.newJob(RecordingJob.class).withIdentity("nightly-close").storeDurably().build();
    }

    @Test
    void fireAndProceedFiresOnceAtRecoveryTimeAndReportsRecoveryTimeAsScheduledTime() throws Exception {
        Instant missedFire = Instant.now().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Scheduler s = newScheduler();
        s.scheduleJob(job(), missedDailyTrigger(
                CronScheduleBuilder.cronSchedule(dailyCronAt(missedFire)).withMisfireHandlingInstructionFireAndProceed(),
                missedFire));
        Instant bootedAt = Instant.now();
        s.start();
        Thread.sleep(3000);

        assertThat(FIRES).hasSize(1);
        Fire fire = FIRES.getFirst();
        // The missed 22:30 is not what the job sees: scheduledFireTime is the moment the misfire was handled.
        assertThat(fire.scheduled()).isAfterOrEqualTo(bootedAt.minusSeconds(1));
        assertThat(Duration.between(missedFire, fire.scheduled())).isGreaterThan(Duration.ofSeconds(100));
        // and the trigger then proceeds with its normal schedule: tomorrow at the cron time
        Date next = s.getTrigger(org.quartz.TriggerKey.triggerKey("nightly-close-cron")).getNextFireTime();
        assertThat(next.toInstant()).isEqualTo(missedFire.plus(Duration.ofDays(1)));
        System.out.printf("FIRE_AND_PROCEED: missed=%s scheduledFireTime=%s fireTime=%s next=%s%n", missedFire,
                fire.scheduled(), fire.actual(), next.toInstant());
    }

    @Test
    void doNothingSkipsTheMissedFireEntirely() throws Exception {
        Instant missedFire = Instant.now().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Scheduler s = newScheduler();
        s.scheduleJob(job(), missedDailyTrigger(
                CronScheduleBuilder.cronSchedule(dailyCronAt(missedFire)).withMisfireHandlingInstructionDoNothing(),
                missedFire));
        s.start();
        Thread.sleep(3000);

        assertThat(FIRES).isEmpty();
        Date next = s.getTrigger(org.quartz.TriggerKey.triggerKey("nightly-close-cron")).getNextFireTime();
        assertThat(next.toInstant()).isEqualTo(missedFire.plus(Duration.ofDays(1)));
        System.out.printf("DO_NOTHING: missed=%s fires=%d next=%s%n", missedFire, FIRES.size(), next.toInstant());
    }

    @Test
    void fireAndProceedIgnoresTheCalendarWhenItFiresTheMissedRun() throws Exception {
        Instant missedFire = Instant.now().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        LocalDate today = Instant.now().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate missedDay = missedFire.atZone(ZoneOffset.UTC).toLocalDate();
        HolidayCalendar todayIsAHoliday = new HolidayCalendar();
        todayIsAHoliday.setTimeZone(TimeZone.getTimeZone("UTC"));
        todayIsAHoliday.addExcludedDate(Date.from(today.atStartOfDay(ZoneOffset.UTC).toInstant()));
        if (!missedDay.equals(today)) { // test ran within two minutes after midnight UTC
            todayIsAHoliday.addExcludedDate(Date.from(missedDay.atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        Scheduler s = newScheduler();
        // Schedule against an empty calendar so the first fire time is the missed one, then swap in the calendar
        // that excludes today without touching the trigger: the state a JDBC store has after downtime over a
        // holiday or weekend (the trigger's next fire time is in the past, and that day is not a business day).
        s.addCalendar("holiday", new HolidayCalendar(), false, false);
        s.scheduleJob(job(), TriggerBuilder.newTrigger().withIdentity("nightly-close-cron")
                .startAt(Date.from(missedFire.minus(Duration.ofMinutes(8))))
                .withSchedule(CronScheduleBuilder.cronSchedule(dailyCronAt(missedFire))
                        .inTimeZone(TimeZone.getTimeZone("UTC")).withMisfireHandlingInstructionFireAndProceed())
                .modifiedByCalendar("holiday")
                .build());
        s.addCalendar("holiday", todayIsAHoliday, true, false);
        s.start();
        Thread.sleep(3000);

        assertThat(FIRES).hasSize(1);
        System.out.printf("FIRE_AND_PROCEED + calendar excluding today: fires=%d scheduledFireTime=%s%n",
                FIRES.size(), FIRES.getFirst().scheduled());
    }

    @Test
    void overwritingTheTriggerOnBootLosesTheMissedFire() throws Exception {
        Instant missedFire = Instant.now().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Scheduler s = newScheduler();
        CronScheduleBuilder schedule = CronScheduleBuilder.cronSchedule(dailyCronAt(missedFire))
                .withMisfireHandlingInstructionFireAndProceed();
        s.scheduleJob(job(), missedDailyTrigger(schedule, missedFire));
        // what spring.quartz.overwrite-existing-jobs=true does on every boot: rescheduleJob with the bean's trigger,
        // whose start time is "now", so its first fire time is tomorrow and nothing ever misfired
        s.rescheduleJob(org.quartz.TriggerKey.triggerKey("nightly-close-cron"), TriggerBuilder.newTrigger()
                .withIdentity("nightly-close-cron").forJob("nightly-close")
                .withSchedule(schedule.inTimeZone(TimeZone.getTimeZone("UTC"))).build());
        s.start();
        Thread.sleep(3000);

        assertThat(FIRES).isEmpty();
        System.out.printf("OVERWRITE ON BOOT: missed=%s fires=%d%n", missedFire, FIRES.size());
    }
}
