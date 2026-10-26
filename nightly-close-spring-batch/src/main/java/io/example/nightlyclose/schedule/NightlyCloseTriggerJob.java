package io.example.nightlyclose.schedule;

import java.time.Instant;
import java.time.LocalDate;

import org.quartz.Calendar;
import org.quartz.CronExpression;
import org.quartz.CronTrigger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.quartz.QuartzJobBean;

import io.example.nightlyclose.RunKey;
import io.example.nightlyclose.chain.ChainService;

/**
 * Fired by the clustered Quartz cron trigger (exactly one node fires it). It only computes the business date and
 * submits the chain; the chain itself runs in Spring Batch, not on a Quartz worker thread.
 */
@DisallowConcurrentExecution
public class NightlyCloseTriggerJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(NightlyCloseTriggerJob.class);

    private final ChainService chains;

    public NightlyCloseTriggerJob(ChainService chains) {
        this.chains = chains;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        // Scheduled fire time, never "now": a trigger that fires late still belongs to the night it was meant for.
        Instant scheduled = context.getScheduledFireTime().toInstant();
        try {
            Calendar calendar = context.getScheduler().getCalendar(QuartzConfig.CALENDAR);
            CronTrigger trigger = (CronTrigger) context.getTrigger();
            CronExpression cron = new CronExpression(trigger.getCronExpression());
            cron.setTimeZone(trigger.getTimeZone());
            LocalDate businessDate = BusinessCalendar.businessDateFor(calendar, cron, scheduled);
            ChainService.Submission submission = chains.submit(new RunKey(businessDate, 1), "quartz");
            log.info("Quartz fire scheduled {} (actual {}) -> business date {} -> HTTP-equivalent {}", scheduled,
                    context.getFireTime().toInstant(), businessDate, submission.httpStatus());
        } catch (SchedulerException | java.text.ParseException e) {
            throw new JobExecutionException(e);
        }
    }
}
