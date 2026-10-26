package org.jobrunr.examples.nightlyclose.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ChainStatus(
        UUID runId,
        LocalDate businessDate,
        int runNumber,
        RunState state,
        Instant submittedAt,
        Instant startedAt,
        Instant finishedAt,
        Partitions partitions,
        List<StepStatus> steps,
        Sla sla,
        String reportFile,
        String reportSha256,
        String deliveryReceipt) {

    public record Partitions(int done, Integer total) {
    }

    /**
     * @param status   PENDING, RUNNING (also: the JVM died inside it and nobody picked it up yet), SUCCEEDED or FAILED
     * @param attempts executions of this step; for "scoring" the sum over all partitions
     */
    public record StepStatus(String step, String status, int attempts, Instant startedAt, Instant finishedAt) {
    }

    /** Lateness is measured against the SLA time on the calendar day after the business date. */
    public record Sla(Instant deadline, boolean late, long latenessSeconds) {
    }
}
