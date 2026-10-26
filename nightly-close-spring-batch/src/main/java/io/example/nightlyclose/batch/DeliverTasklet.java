package io.example.nightlyclose.batch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.example.nightlyclose.ChainProperties;
import io.example.nightlyclose.RunKey;
import io.example.nightlyclose.tracking.Markers;

/**
 * Sends the report to the regulator with the idempotency key, then records a receipt. A kill between the two
 * means the regulator has it and we do not know: the rerun sends again and the regulator rejects the duplicate.
 * The tasklet runs inside one transaction, so the receipt insert only becomes visible when the step commits.
 */
@Component
public class DeliverTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(DeliverTasklet.class);

    private final JdbcTemplate jdbc;
    private final ChainProperties properties;
    private final ReportTasklet report;
    private final Markers markers;
    private final String regulatorUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public DeliverTasklet(JdbcTemplate jdbc, ChainProperties properties, ReportTasklet report, Markers markers,
            @Value("${server.port:8080}") int port) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.report = report;
        this.markers = markers;
        String configured = properties.regulator().url();
        this.regulatorUrl = configured.isBlank() ? "http://localhost:" + port + "/regulator/submissions" : configured;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        RunKey key = RunKey.of(chunkContext.getStepContext().getStepExecution().getJobParameters());
        Integer receipts = jdbc.queryForObject("SELECT count(*) FROM delivery_receipts WHERE idempotency_key = ?",
                Integer.class, key.idempotencyKey());
        if (receipts > 0) {
            log.info("{}: receipt on record, not sending again", key.runId());
            return RepeatStatus.FINISHED;
        }
        Path file = report.reportFile(key);
        markers.enter("deliver-before-call");
        Thread.sleep(properties.testHooks().deliverSleepBeforeCall());

        HttpRequest request = HttpRequest.newBuilder(URI.create(regulatorUrl + "?businessDate=" + key.businessDate()
                        + "&runNumber=" + key.runNumber()))
                .header("Idempotency-Key", key.idempotencyKey())
                .header("Content-Type", "text/csv")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofFile(file))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        // 201 = accepted now, 409 = the regulator already holds this key: both mean "the regulator has it".
        if (response.statusCode() != 201 && response.statusCode() != 409) {
            throw new IllegalStateException("Regulator answered " + response.statusCode() + ": " + response.body());
        }
        markers.enter("deliver-after-call-before-record");
        Thread.sleep(properties.testHooks().deliverSleepAfterCall());

        jdbc.update("""
                INSERT INTO delivery_receipts (idempotency_key, run_id, regulator_status, recorded_at)
                VALUES (?, ?, ?, clock_timestamp())""", key.idempotencyKey(), key.runId(), response.statusCode());
        log.info("{}: delivered, regulator answered {}", key.runId(), response.statusCode());
        return RepeatStatus.FINISHED;
    }
}
