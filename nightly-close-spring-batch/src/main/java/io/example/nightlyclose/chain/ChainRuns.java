package io.example.nightlyclose.chain;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import io.example.nightlyclose.RunKey;

/** Our own row per run: the input hash (for the 409 rule), the owning node and the hand-over flag. */
@Repository
public class ChainRuns {

    public record ChainRun(String runId, String inputHash, String ownerNode, Long jobExecutionId, boolean handover,
            Integer partitionsTotal, String triggerSource) {
    }

    private final JdbcTemplate jdbc;

    public ChainRuns(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ChainRun> find(RunKey key) {
        return findByRunId(key.runId());
    }

    public Optional<ChainRun> findByRunId(String runId) {
        List<ChainRun> rows = jdbc.query("""
                SELECT run_id, input_hash, owner_node, job_execution_id, handover, partitions_total, trigger_source
                FROM chain_runs WHERE run_id = ?""",
                (rs, i) -> new ChainRun(rs.getString(1), rs.getString(2), rs.getString(3),
                        (Long) rs.getObject(4), rs.getBoolean(5), (Integer) rs.getObject(6), rs.getString(7)),
                runId);
        return rows.stream().findFirst();
    }

    /** @return true if this call registered the run, false if it already existed. */
    public boolean register(RunKey key, String inputHash, String triggerSource) {
        return jdbc.update("""
                INSERT INTO chain_runs (run_id, business_date, run_number, input_hash, trigger_source)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (run_id) DO NOTHING""",
                key.runId(), key.businessDate(), key.runNumber(), inputHash, triggerSource) == 1;
    }

    /** Records the hash the first time the file is seen; returns the hash that is on record afterwards. */
    public String recordInputHash(RunKey key, String inputHash) {
        jdbc.update("UPDATE chain_runs SET input_hash = ? WHERE run_id = ? AND input_hash IS NULL", inputHash, key.runId());
        return jdbc.queryForObject("SELECT input_hash FROM chain_runs WHERE run_id = ?", String.class, key.runId());
    }

    public void claim(RunKey key, String nodeName, long jobExecutionId) {
        jdbc.update("UPDATE chain_runs SET owner_node = ?, job_execution_id = ?, handover = false WHERE run_id = ?",
                nodeName, jobExecutionId, key.runId());
    }

    /** Compare-and-set so two surviving nodes never both restart the same orphan. */
    public boolean takeOver(String runId, String expectedOwner, String newOwner) {
        return jdbc.update("""
                UPDATE chain_runs SET owner_node = ?, handover = false
                WHERE run_id = ? AND owner_node IS NOT DISTINCT FROM ?""", newOwner, runId, expectedOwner) == 1;
    }

    public void requestHandover(String runId) {
        jdbc.update("UPDATE chain_runs SET handover = true WHERE run_id = ?", runId);
    }

    public void recordPartitions(RunKey key, int total) {
        jdbc.update("UPDATE chain_runs SET partitions_total = ? WHERE run_id = ?", total, key.runId());
    }
}
