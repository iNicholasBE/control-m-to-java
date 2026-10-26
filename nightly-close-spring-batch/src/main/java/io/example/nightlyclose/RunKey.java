package io.example.nightlyclose;

import java.time.LocalDate;

import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;

/** The deterministic identity of one run of the chain: business date plus run number. */
public record RunKey(LocalDate businessDate, int runNumber) {

    public static final String JOB_NAME = "nightlyClose";

    public static RunKey of(JobParameters parameters) {
        LocalDate businessDate = parameters.getLocalDate("businessDate");
        Long runNumber = parameters.getLong("runNumber");
        return new RunKey(businessDate, runNumber.intValue());
    }

    public String runId() {
        return "NIGHTLY-CLOSE-" + businessDate + "-" + runNumber;
    }

    /** Same string as the run id on purpose: one run, one delivery. */
    public String idempotencyKey() {
        return runId();
    }

    public JobParameters toJobParameters() {
        return new JobParametersBuilder()
                .addLocalDate("businessDate", businessDate, true)
                .addLong("runNumber", (long) runNumber, true)
                .toJobParameters();
    }
}
