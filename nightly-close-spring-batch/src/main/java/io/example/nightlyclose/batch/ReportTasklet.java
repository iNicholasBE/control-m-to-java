package io.example.nightlyclose.batch;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.example.nightlyclose.ChainProperties;
import io.example.nightlyclose.RunKey;

/**
 * Writes REPORT-&lt;businessDate&gt;-&lt;runNumber&gt;.csv. No timestamps in the content, so a rerun produces the same
 * bytes; written to a temp file and moved atomically, so a kill never leaves a half report under the real name.
 */
@Component
public class ReportTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(ReportTasklet.class);

    private final JdbcTemplate jdbc;
    private final ChainProperties properties;

    public ReportTasklet(JdbcTemplate jdbc, ChainProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public Path reportFile(RunKey key) {
        return properties.outbox().resolve("REPORT-" + key.businessDate() + "-" + key.runNumber() + ".csv");
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        RunKey key = RunKey.of(chunkContext.getStepContext().getStepExecution().getJobParameters());
        Files.createDirectories(properties.outbox());
        Path target = reportFile(key);
        Path temp = properties.outbox().resolve(target.getFileName() + ".tmp");
        try (BufferedWriter out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            out.write("business_date,run_number,portfolio,positions,total_score,max_score,avg_score\n");
            jdbc.query("""
                    SELECT portfolio, positions, total_score, max_score, avg_score FROM portfolio_aggregates
                    WHERE run_id = ? ORDER BY portfolio""", rs -> {
                try {
                    out.write(String.join(",", key.businessDate().toString(), String.valueOf(key.runNumber()),
                            rs.getString(1), rs.getString(2), rs.getBigDecimal(3).toPlainString(),
                            rs.getBigDecimal(4).toPlainString(), rs.getBigDecimal(5).toPlainString()) + "\n");
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }, key.runId());
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log.info("{}: report written to {}", key.runId(), target);
        return RepeatStatus.FINISHED;
    }
}
