package org.jobrunr.examples.nightlyclose.pro;

import org.jobrunr.examples.nightlyclose.NightlyCloseProperties;
import org.jobrunr.examples.nightlyclose.bridge.BridgeFileWatcher;
import org.jobrunr.examples.nightlyclose.calendar.NightlyTrigger;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.jobrunr.jobs.context.JobContext;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.jobrunr.scheduling.RecurringJobBuilder.aRecurringJob;

@Component
public class ProTriggers {

    private static final Logger LOG = LoggerFactory.getLogger(ProTriggers.class);
    private static final String TRIGGER_ID = "nightly-close-trigger";

    private final JobScheduler jobScheduler;
    private final NightlyTrigger trigger;
    private final BridgeFileWatcher bridge;
    private final NightlyCloseProperties properties;
    private final String cron;

    public ProTriggers(JobScheduler jobScheduler, NightlyTrigger trigger, BridgeFileWatcher bridge,
                       NightlyCloseProperties properties, @Value("${nightly-close.trigger.cron}") String cron) {
        this.jobScheduler = jobScheduler;
        this.trigger = trigger;
        this.bridge = bridge;
        this.properties = properties;
        this.cron = cron;
    }

    /**
     * The cron trigger on the business-day calendar, in UTC. If every server was down at fire time, Pro schedules
     * the missed run once a server is back ({@code withScheduleJobsSkippedDuringDowntime}); OSS silently skips it.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerNightlyTrigger() {
        if ("-".equals(cron)) {
            jobScheduler.deleteRecurringJob(TRIGGER_ID);
            return;
        }
        jobScheduler.createRecurrently(aRecurringJob()
                .withId(TRIGGER_ID)
                .withName("Nightly close trigger")
                .withCustomSchedule(BusinessDaySchedule.definition(cron, properties.holidaysFile()))
                .withZoneId(ZoneOffset.UTC)
                .withScheduleJobsSkippedDuringDowntime()
                .<ProTriggers>withJobLambda(triggers -> triggers.nightlyClose(JobContext.Null)));
    }

    public void nightlyClose(JobContext jobContext) {
        String businessDate = jobContext.runStepOnce("resolve-business-date",
                () -> trigger.businessDateFor(jobContext.getCreatedAt()).map(LocalDate::toString).orElse(""));
        if (businessDate.isEmpty()) {
            LOG.info("Nightly close trigger fired on a non-business day; nothing to close");
            return;
        }
        jobContext.runStepOnce("submit", () -> trigger.submit(LocalDate.parse(businessDate)));
    }

    @Recurring(id = "bridge-file-watcher", interval = "PT5S")
    @Job(name = "Control-M bridge: scan for READY files")
    public void scanBridgeInbox() throws IOException {
        bridge.scanInbox();
    }
}
