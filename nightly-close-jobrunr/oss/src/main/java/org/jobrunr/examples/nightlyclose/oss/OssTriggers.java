package org.jobrunr.examples.nightlyclose.oss;

import org.jobrunr.examples.nightlyclose.bridge.BridgeFileWatcher;
import org.jobrunr.examples.nightlyclose.calendar.NightlyTrigger;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.jobrunr.jobs.context.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;

/**
 * The two ways a run starts besides the REST call: the cron trigger and the Control-M bridge's file drop.
 */
@Component
public class OssTriggers {

    private static final Logger LOG = LoggerFactory.getLogger(OssTriggers.class);

    private final NightlyTrigger trigger;
    private final BridgeFileWatcher bridge;

    public OssTriggers(NightlyTrigger trigger, BridgeFileWatcher bridge) {
        this.trigger = trigger;
        this.bridge = bridge;
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

    @Recurring(id = "bridge-file-watcher", interval = "PT5S")
    @Job(name = "Control-M bridge: scan for READY files")
    public void scanBridgeInbox() throws IOException {
        bridge.scanInbox();
    }
}
