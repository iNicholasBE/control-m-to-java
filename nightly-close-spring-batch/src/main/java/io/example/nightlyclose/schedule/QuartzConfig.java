package io.example.nightlyclose.schedule;

import java.util.TimeZone;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.calendar.HolidayCalendar;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.example.nightlyclose.ChainProperties;

@Configuration
public class QuartzConfig {

    public static final String CALENDAR = "businessDays";

    /** Bean name = calendar name; Spring Boot hands every Calendar bean to the scheduler. */
    @Bean(CALENDAR)
    public HolidayCalendar businessDays(ChainProperties properties) {
        return BusinessCalendar.load(properties.holidays());
    }

    @Bean
    public JobDetail nightlyCloseTriggerJob() {
        return JobBuilder.newJob(NightlyCloseTriggerJob.class)
                .withIdentity("nightly-close", "chains")
                .storeDurably()
                .build();
    }

    /**
     * Cron in UTC so a DST change never moves or doubles the fire. FireAndProceed: if every node was down at
     * 22:30, the first node back fires once, and the job still derives the business date from the scheduled time.
     */
    @Bean
    @ConditionalOnProperty(name = "chain.schedule.enabled", havingValue = "true", matchIfMissing = true)
    public Trigger nightlyCloseCron(JobDetail nightlyCloseTriggerJob, ChainProperties properties) {
        return TriggerBuilder.newTrigger()
                .forJob(nightlyCloseTriggerJob)
                .withIdentity("nightly-close-cron", "chains")
                .withSchedule(CronScheduleBuilder.cronSchedule(properties.schedule().cron())
                        .inTimeZone(TimeZone.getTimeZone("UTC"))
                        .withMisfireHandlingInstructionFireAndProceed())
                .modifiedByCalendar(CALENDAR)
                .build();
    }
}
