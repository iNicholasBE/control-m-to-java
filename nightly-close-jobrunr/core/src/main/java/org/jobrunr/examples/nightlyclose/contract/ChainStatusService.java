package org.jobrunr.examples.nightlyclose.contract;

import org.jobrunr.examples.nightlyclose.NightlyCloseProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ChainStatusService {

    /** The six Control-M jobs plus the join, in flow order. Partitions are folded into "scoring". */
    private static final List<String> STEPS = List.of("watcher", "extract", "scoring", "join", "aggregate", "report", "deliver");

    private final ChainRunRepository runs;
    private final JdbcClient jdbc;
    private final NightlyCloseProperties properties;

    public ChainStatusService(ChainRunRepository runs, JdbcClient jdbc, NightlyCloseProperties properties) {
        this.runs = runs;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public Optional<ChainStatus> statusOf(UUID runId) {
        return runs.find(runId).map(this::statusOf);
    }

    public Instant slaDeadline(LocalDate businessDate) {
        return businessDate.plusDays(1).atTime(properties.slaTime()).atZone(properties.slaZone()).toInstant();
    }

    private ChainStatus statusOf(ChainRun run) {
        int partitionsDone = jdbc.sql("SELECT count(*) FROM partition_progress WHERE run_id = ?")
                .param(run.runId()).query(Integer.class).single();
        var steps = new ArrayList<ChainStatus.StepStatus>();
        for (String step : STEPS) steps.add(stepStatus(run, step, partitionsDone));

        Instant deadline = slaDeadline(run.businessDate());
        Instant reference = run.finishedAt() != null ? run.finishedAt() : Instant.now();
        long lateness = Duration.between(deadline, reference).toSeconds();

        return new ChainStatus(run.runId(), run.businessDate(), run.runNumber(), run.state(),
                run.submittedAt(), run.startedAt(), run.finishedAt(),
                new ChainStatus.Partitions(partitionsDone, run.partitionsTotal()),
                steps,
                new ChainStatus.Sla(deadline, lateness > 0, Math.max(lateness, 0)),
                run.reportFile(), run.reportSha256(), run.deliveryReceipt());
    }

    private ChainStatus.StepStatus stepStatus(ChainRun run, String step, int partitionsDone) {
        String filter = step.equals("scoring") ? "step LIKE 'scoring-partition-%'" : "step = :step";
        record Row(int attempts, Timestamp started, Timestamp finished, int open, int failed, int succeeded) {
        }
        Row row = jdbc.sql("""
                        SELECT count(*) AS attempts, min(started_at) AS started, max(finished_at) AS finished,
                               count(*) FILTER (WHERE outcome IS NULL) AS open,
                               count(*) FILTER (WHERE outcome = 'FAILED') AS failed,
                               count(*) FILTER (WHERE outcome = 'SUCCEEDED') AS succeeded
                          FROM step_executions WHERE run_id = :runId AND\s""" + filter)
                .param("runId", run.runId()).param("step", step)
                .query((rs, n) -> new Row(rs.getInt("attempts"), rs.getTimestamp("started"), rs.getTimestamp("finished"),
                        rs.getInt("open"), rs.getInt("failed"), rs.getInt("succeeded")))
                .single();

        String status;
        if (row.attempts() == 0) status = "PENDING";
        else if (step.equals("scoring")) {
            boolean allDone = run.partitionsTotal() != null && partitionsDone == run.partitionsTotal();
            status = allDone ? "SUCCEEDED" : "RUNNING";
        } else status = latestOutcome(run, step);

        Instant finished = status.equals("SUCCEEDED") && row.finished() != null ? row.finished().toInstant() : null;
        return new ChainStatus.StepStatus(step, status, row.attempts(),
                row.started() == null ? null : row.started().toInstant(), finished);
    }

    private String latestOutcome(ChainRun run, String step) {
        return jdbc.sql("""
                        SELECT coalesce(outcome, 'RUNNING') FROM step_executions
                         WHERE run_id = ? AND step = ? ORDER BY attempt DESC LIMIT 1""")
                .params(run.runId(), step).query(String.class).single();
    }
}
