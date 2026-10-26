package org.jobrunr.examples.nightlyclose.oss;

import org.jobrunr.examples.nightlyclose.bridge.InboxWatcher;
import org.jobrunr.examples.nightlyclose.calendar.NightlyTrigger;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.jobrunr.jobs.context.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * The recurring jobs that start runs: the nightly cron trigger and the inbox watcher (positions files and the
 * Control-M bridge's READY files). The REST endpoint is the third way in.
 */
@Component
public class OssTriggers {

    private static final Logger LOG = LoggerFactory.getLogger(OssTriggers.class);

    private final NightlyTrigger trigger;
    private final InboxWatcher inbox;

    public OssTriggers(NightlyTrigger trigger, InboxWatcher inbox) {
        this.trigger = trigger;
        this.inbox = inbox;
    }

    /**
     * Cron in UTC on purpose: a local-time cron fires twice or not at all on DST change nights (see DstTest).
     * OSS cron has no holiday calendar, so the holiday check happens inside the job.
     */
    @Recurring(id = "nightly-close-trigger", cron = "${nightly-close.trigger.cron}", zoneId = "UTC")
    @Job(name = "Nightly close trigger")
    public void nightlyClose(JobContext jobContext) {
        // Resolved once and replayed on a retry, so a trigger that crashes after midnight still closes the right day.
        String businessDate = jobContext.runStepOnce("resolve-business-date",
                () -> trigger.businessDateFor(jobContext.getCreatedAt()).map(LocalDate::toString).orElse(""));
        if (businessDate.isEmpty()) {
            LOG.info("Nightly close trigger fired on a non-business day; nothing to close");
            return;
        }
        jobContext.runStepOnce("submit", () -> trigger.submit(LocalDate.parse(businessDate)));
    }

    /**
     * Step 1, the file watcher, plus the bridge's READY files. Every 15 seconds: JobRunr OSS needs the poll
     * interval (5 s, the minimum on SQL storage) to be shorter than its most frequent recurring job.
     */
    @Recurring(id = "inbox-watcher", interval = "PT15S")
    @Job(name = "Inbox watcher: positions files and READY files")
    public void scanInbox() throws Exception {
        inbox.scan();
    }
}
