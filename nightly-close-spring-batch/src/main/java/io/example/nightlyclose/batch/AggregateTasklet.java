package io.example.nightlyclose.batch;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.example.nightlyclose.RunKey;

/** Delete-then-insert inside the tasklet's single transaction, so a rerun after a crash starts from a clean slate. */
@Component
public class AggregateTasklet implements Tasklet {

    private final JdbcTemplate jdbc;

    public AggregateTasklet(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        String runId = RunKey.of(chunkContext.getStepContext().getStepExecution().getJobParameters()).runId();
        jdbc.update("DELETE FROM portfolio_aggregates WHERE run_id = ?", runId);
        int portfolios = jdbc.update("""
                INSERT INTO portfolio_aggregates (run_id, portfolio, positions, total_score, max_score, avg_score)
                SELECT run_id, portfolio, count(*), sum(score), max(score), round(avg(score), 4)
                FROM risk_scores WHERE run_id = ? GROUP BY run_id, portfolio""", runId);
        contribution.incrementWriteCount(portfolios);
        return RepeatStatus.FINISHED;
    }
}
