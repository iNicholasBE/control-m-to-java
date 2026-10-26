package io.example.nightlyclose.batch;

/** One CSV line as it sits in the staging table: line number (natural key), portfolio, and the raw 40 columns. */
public record StagedPosition(long lineNo, String portfolio, String raw) {
}
