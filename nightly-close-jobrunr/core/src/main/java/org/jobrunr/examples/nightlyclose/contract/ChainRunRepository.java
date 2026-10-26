package org.jobrunr.examples.nightlyclose.contract;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ChainRunRepository {

    private final JdbcClient jdbc;

    public ChainRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true when this call created the row, false when it already existed */
    public boolean insertIfAbsent(UUID runId, LocalDate businessDate, int runNumber, String inputSha256) {
        return jdbc.sql("""
                        INSERT INTO chain_runs (run_id, business_date, run_number, input_sha256, state)
                        VALUES (?, ?, ?, ?, 'SUBMITTED')
                        ON CONFLICT (run_id) DO NOTHING""")
                .params(runId, businessDate, runNumber, inputSha256)
                .update() == 1;
    }

    public Optional<ChainRun> find(UUID runId) {
        return jdbc.sql("SELECT * FROM chain_runs WHERE run_id = ?").param(runId).query(this::map).optional();
    }

    public ChainRun get(UUID runId) {
        return find(runId).orElseThrow(() -> new IllegalStateException("Unknown run " + runId));
    }

    /** Moves the run forward only; a replayed step never moves a run back. */
    public void advance(UUID runId, RunState from, RunState to) {
        jdbc.sql("UPDATE chain_runs SET state = ? WHERE run_id = ? AND state = ?")
                .params(to.name(), runId, from.name()).update();
    }

    public List<ChainRun> findInState(RunState state) {
        return jdbc.sql("SELECT * FROM chain_runs WHERE state = ? ORDER BY submitted_at").param(state.name()).query(this::map).list();
    }

    public void markStarted(UUID runId, String inputSha256) {
        jdbc.sql("""
                        UPDATE chain_runs
                           SET started_at = coalesce(started_at, clock_timestamp()),
                               input_sha256 = coalesce(input_sha256, ?),
                               state = CASE WHEN state IN ('SUBMITTED', 'LAUNCHED') THEN 'RUNNING' ELSE state END
                         WHERE run_id = ?""")
                .params(inputSha256, runId).update();
    }

    /**
     * Moves SCORING to JOINED when every partition has a progress row. The row lock on chain_runs makes
     * exactly one caller win, even when the last two partitions commit at the same moment.
     */
    public boolean joinWhenAllPartitionsDone(UUID runId) {
        return jdbc.sql("""
                        UPDATE chain_runs SET state = 'JOINED'
                         WHERE run_id = ? AND state = 'SCORING'
                           AND partitions_total = (SELECT count(*) FROM partition_progress WHERE run_id = ?)""")
                .params(runId, runId).update() == 1;
    }

    /** Runs in SCORING whose partitions are all done: the OSS join poller's query. */
    public List<UUID> findScoringRunsWithAllPartitionsDone() {
        return jdbc.sql("""
                        SELECT r.run_id FROM chain_runs r
                         WHERE r.state = 'SCORING'
                           AND r.partitions_total = (SELECT count(*) FROM partition_progress p WHERE p.run_id = r.run_id)
                         ORDER BY r.submitted_at""")
                .query(UUID.class).list();
    }

    public void setPartitionsTotal(UUID runId, int partitions) {
        jdbc.sql("UPDATE chain_runs SET partitions_total = ? WHERE run_id = ?").params(partitions, runId).update();
    }

    public void setReport(UUID runId, String reportFile, String reportSha256) {
        jdbc.sql("UPDATE chain_runs SET report_file = ?, report_sha256 = ? WHERE run_id = ?")
                .params(reportFile, reportSha256, runId).update();
    }

    public void setDeliveryReceipt(UUID runId, String receipt) {
        jdbc.sql("UPDATE chain_runs SET delivery_receipt = ? WHERE run_id = ?").params(receipt, runId).update();
    }

    public void markDone(UUID runId) {
        jdbc.sql("""
                        UPDATE chain_runs SET state = 'DONE', finished_at = coalesce(finished_at, clock_timestamp())
                         WHERE run_id = ?""")
                .param(runId).update();
    }

    private ChainRun map(ResultSet rs, int row) throws SQLException {
        return new ChainRun(
                rs.getObject("run_id", UUID.class),
                rs.getObject("business_date", LocalDate.class),
                rs.getInt("run_number"),
                rs.getString("input_sha256"),
                RunState.valueOf(rs.getString("state")),
                (Integer) rs.getObject("partitions_total"),
                instant(rs.getTimestamp("submitted_at")),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                rs.getString("report_file"),
                rs.getString("report_sha256"),
                rs.getString("delivery_receipt"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
