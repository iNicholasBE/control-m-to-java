package org.jobrunr.examples.nightlyclose;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Deterministic identities. The same business date and run number always map to the same ids,
 * which is what makes re-submitting a run, or re-creating one of its jobs, a no-op.
 */
public final class ChainIds {

    private ChainIds() {
    }

    public static UUID runId(LocalDate businessDate, int runNumber) {
        return nameBased("nightly-close|" + businessDate + "|" + runNumber);
    }

    /** Id of a job that belongs to a run, e.g. {@code jobId(runId, "scoring-partition-3")}. */
    public static UUID jobId(UUID runId, String part) {
        return nameBased(runId + "|" + part);
    }

    public static String idempotencyKey(LocalDate businessDate, int runNumber) {
        return "NIGHTLY-CLOSE-" + businessDate + "-" + runNumber;
    }

    private static UUID nameBased(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
