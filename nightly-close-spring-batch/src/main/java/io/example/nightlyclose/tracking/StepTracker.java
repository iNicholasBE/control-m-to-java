package io.example.nightlyclose.tracking;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.example.nightlyclose.RunKey;
import io.example.nightlyclose.recovery.NodeIdentity;

/**
 * Writes the contract table step_executions: one row on entry, completed on exit. Spring Batch keeps its own
 * BATCH_STEP_EXECUTION rows; this table exists so one harness can compare this implementation with JobRunr.
 */
@Component
public class StepTracker implements StepExecutionListener {

    private final JdbcTemplate jdbc;
    private final Markers markers;
    private final NodeIdentity node;
    private final Map<Long, Integer> attempts = new ConcurrentHashMap<>();

    public StepTracker(JdbcTemplate jdbc, Markers markers, NodeIdentity node) {
        this.jdbc = jdbc;
        this.markers = markers;
        this.node = node;
    }

    /** "scoring-worker:scoring-partition-3" (Spring Batch's partition step name) becomes "scoring-partition-3". */
    public static String contractName(String springBatchStepName) {
        int colon = springBatchStepName.indexOf(':');
        return colon < 0 ? springBatchStepName : springBatchStepName.substring(colon + 1);
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        String step = contractName(stepExecution.getStepName());
        String runId = RunKey.of(stepExecution.getJobParameters()).runId();
        Integer attempt = jdbc.queryForObject("""
                INSERT INTO step_executions (run_id, step, attempt, started_at, outcome, node, job_execution_id)
                SELECT ?, ?, coalesce(max(attempt), 0) + 1, clock_timestamp(), 'RUNNING', ?, ?
                FROM step_executions WHERE run_id = ? AND step = ?
                RETURNING attempt""", Integer.class,
                runId, step, node.nodeName(), stepExecution.getJobExecutionId(), runId, step);
        attempts.put(stepExecution.getId(), attempt);
        markers.enter(step);
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        Integer attempt = attempts.remove(stepExecution.getId());
        if (attempt != null) {
            jdbc.update("""
                    UPDATE step_executions SET finished_at = clock_timestamp(), outcome = ?
                    WHERE run_id = ? AND step = ? AND attempt = ?""",
                    stepExecution.getStatus().name(), RunKey.of(stepExecution.getJobParameters()).runId(),
                    contractName(stepExecution.getStepName()), attempt);
        }
        return stepExecution.getExitStatus();
    }
}
