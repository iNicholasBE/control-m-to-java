package org.jobrunr.examples.nightlyclose.bookkeeping;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Writes a {@code step_executions} row when a step starts and completes it when the step ends.
 * The rows are committed on their own, outside the step's business transaction, so the start row of a
 * step that was killed survives. That is how the harness counts steps that ran twice.
 */
@Component
public class StepRecorder {

    @FunctionalInterface
    public interface StepBody {
        void run() throws Exception;
    }

    private final JdbcClient jdbc;
    private final Markers markers;

    public StepRecorder(JdbcClient jdbc, Markers markers) {
        this.jdbc = jdbc;
        this.markers = markers;
    }

    public void run(UUID runId, String step, StepBody body) throws Exception {
        call(runId, step, () -> {
            body.run();
            return null;
        });
    }

    public <T> T call(UUID runId, String step, Callable<T> body) throws Exception {
        long executionId = start(runId, step);
        markers.enter(step);
        try {
            T result = body.call();
            finish(executionId, "SUCCEEDED");
            return result;
        } catch (Exception | Error e) {
            finish(executionId, "FAILED");
            throw e;
        }
    }

    private long start(UUID runId, String step) {
        return jdbc.sql("""
                        INSERT INTO step_executions (run_id, step, attempt)
                        SELECT ?, ?, coalesce(max(attempt), 0) + 1 FROM step_executions WHERE run_id = ? AND step = ?
                        RETURNING id""")
                .params(runId, step, runId, step)
                .query(Long.class).single();
    }

    private void finish(long executionId, String outcome) {
        jdbc.sql("UPDATE step_executions SET finished_at = clock_timestamp(), outcome = ? WHERE id = ?")
                .params(outcome, executionId).update();
    }
}
