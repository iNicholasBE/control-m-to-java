package org.jobrunr.examples.nightlyclose.steps;

/** A scoring partition: one portfolio. Numbers start at 1 and follow the sorted portfolio codes. */
public record Partition(int number, String portfolio) {

    public String stepName() {
        return "scoring-partition-" + number;
    }
}
