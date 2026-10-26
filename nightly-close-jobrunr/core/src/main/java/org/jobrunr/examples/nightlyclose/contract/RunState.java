package org.jobrunr.examples.nightlyclose.contract;

public enum RunState {
    /** Row exists, jobs not yet created (or the JVM died in between). */
    SUBMITTED,
    /** The scheduler accepted the jobs. */
    LAUNCHED,
    RUNNING,
    SCORING,
    /** All partitions are scored and the join has fired. */
    JOINED,
    AGGREGATING,
    DONE
}
