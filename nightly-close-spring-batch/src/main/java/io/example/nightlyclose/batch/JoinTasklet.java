package io.example.nightlyclose.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.example.nightlyclose.RunKey;

/**
 * The partition step already blocks until every worker is done (Spring Batch's built-in join). This step is the
 * check a Control-M flow would put after the fan-in: every staged row has exactly one score.
 */
@Component
public class JoinTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(JoinTasklet.class);

    private final JdbcTemplate jdbc;

    public JoinTasklet(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        String runId = RunKey.of(chunkContext.getStepContext().getStepExecution().getJobParameters()).runId();
        Long staged = jdbc.queryForObject("SELECT count(*) FROM positions_staging WHERE run_id = ?", Long.class, runId);
        Long scored = jdbc.queryForObject("SELECT count(*) FROM risk_scores WHERE run_id = ?", Long.class, runId);
        if (!staged.equals(scored)) {
            throw new IllegalStateException(runId + ": staged " + staged + " rows but scored " + scored);
        }
        log.info("{}: join ok, {} rows scored across all partitions", runId, scored);
        return RepeatStatus.FINISHED;
    }
}
