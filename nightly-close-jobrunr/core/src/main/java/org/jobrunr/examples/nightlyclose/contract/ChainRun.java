package org.jobrunr.examples.nightlyclose.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ChainRun(
        UUID runId,
        LocalDate businessDate,
        int runNumber,
        String inputSha256,
        RunState state,
        Integer partitionsTotal,
        Instant submittedAt,
        Instant startedAt,
        Instant finishedAt,
        String reportFile,
        String reportSha256,
        String deliveryReceipt) {
}
