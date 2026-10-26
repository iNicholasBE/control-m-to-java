package org.jobrunr.examples.nightlyclose.calendar;

import org.jobrunr.examples.nightlyclose.bookkeeping.Markers;
import org.jobrunr.examples.nightlyclose.contract.EnqueueContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * What the recurring trigger does once the scheduler fired it. The business date is derived from the fire time in
 * UTC, once, and from then on travels as a parameter; no step ever asks the clock which day it is.
 */
@Component
public class NightlyTrigger {

    private static final Logger LOG = LoggerFactory.getLogger(NightlyTrigger.class);

    private final BusinessCalendar calendar;
    private final EnqueueContract contract;
    private final Markers markers;

    public NightlyTrigger(BusinessCalendar calendar, EnqueueContract contract, Markers markers) {
        this.calendar = calendar;
        this.contract = contract;
        this.markers = markers;
    }

    /** The close runs in the evening of the business day it closes, so the business date is the UTC date of the fire time. */
    public Optional<LocalDate> businessDateFor(Instant fireTime) {
        LocalDate date = fireTime.atZone(ZoneOffset.UTC).toLocalDate();
        return calendar.isBusinessDay(date) ? Optional.of(date) : Optional.empty();
    }

    public void submit(LocalDate businessDate) {
        markers.enter("recurring-trigger");
        var submission = contract.submit(businessDate, 1);
        LOG.info("Recurring trigger for {}: {}", businessDate, submission.outcome());
    }
}
