package io.example.nightlyclose.chain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import io.example.nightlyclose.RunKey;

/**
 * The enqueue contract. Identity is (businessDate, runNumber) = the identifying JobParameters, so Spring Batch's
 * own JobInstance uniqueness does the deduplication; this class maps its exceptions onto 200 / 202 / 409.
 */
@Service
public class ChainService {

    private static final Logger log = LoggerFactory.getLogger(ChainService.class);

    public record Submission(int httpStatus, Map<String, Object> body) {
    }

    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final Job nightlyClose;
    private final ChainRuns chainRuns;
    private final InputFiles inputFiles;
    private final Sla sla;
    private final JdbcTemplate jdbc;

    public ChainService(JobOperator jobOperator, JobRepository jobRepository, Job nightlyClose, ChainRuns chainRuns,
            InputFiles inputFiles, Sla sla, JdbcTemplate jdbc) {
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.nightlyClose = nightlyClose;
        this.chainRuns = chainRuns;
        this.inputFiles = inputFiles;
        this.sla = sla;
        this.jdbc = jdbc;
    }

    public Submission submit(RunKey key, String triggerSource) {
        String hash = inputFiles.hashIfPresent(key.businessDate()).orElse(null);
        Optional<ChainRuns.ChainRun> existing = chainRuns.find(key);
        if (existing.isPresent()) {
            String onRecord = existing.get().inputHash();
            if (hash != null && onRecord != null && !onRecord.equals(hash)) {
                JobExecution last = lastExecution(key);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("runId", key.runId());
                body.put("executionId", last == null ? null : last.getId());
                body.put("reason", "Input file hash " + hash + " differs from " + onRecord
                        + " recorded for this run; submit a new runNumber for a rerun");
                return new Submission(409, body);
            }
        }
        boolean registeredNow = chainRuns.register(key, hash, triggerSource);
        JobExecution last = lastExecution(key);
        if (last != null && (last.isRunning() || last.getStatus() == BatchStatus.COMPLETED)) {
            return new Submission(200, status(key).orElseThrow());
        }
        try {
            // For a FAILED or STOPPED instance this same call is Spring Batch's restart: same identifying
            // parameters, new JobExecution, COMPLETED steps skipped.
            JobExecution execution = jobOperator.start(nightlyClose, key.toJobParameters());
            log.info("{}: {} job execution {} (trigger {})", key.runId(), last == null ? "started" : "restarted",
                    execution.getId(), triggerSource);
            Map<String, Object> body = status(key).orElseThrow();
            body.put("restarted", last != null);
            return new Submission(registeredNow && last == null ? 202 : 200, body);
        } catch (JobExecutionAlreadyRunningException | JobInstanceAlreadyCompleteException
                | ConcurrencyFailureException | DataIntegrityViolationException alreadyThere) {
            // a concurrent submit of the same identity won the race to create the JobInstance
            return new Submission(200, status(key).orElseThrow());
        } catch (Exception e) {
            throw new IllegalStateException("Could not start " + key.runId(), e);
        }
    }

    public JobExecution lastExecution(RunKey key) {
        return jobRepository.getLastJobExecution(RunKey.JOB_NAME, key.toJobParameters());
    }

    public Optional<Map<String, Object>> status(RunKey key) {
        Optional<ChainRuns.ChainRun> run = chainRuns.find(key);
        if (run.isEmpty()) {
            return Optional.empty();
        }
        JobInstance instance = jobRepository.getJobInstance(RunKey.JOB_NAME, key.toJobParameters());
        List<JobExecution> executions = instance == null ? List.of()
                : jobRepository.getJobExecutions(instance).stream()
                        .sorted(Comparator.comparingLong(JobExecution::getId)).toList();
        JobExecution first = executions.isEmpty() ? null : executions.getFirst();
        JobExecution last = executions.isEmpty() ? null : executions.getLast();

        List<Map<String, Object>> steps = new ArrayList<>();
        int partitionsDone = 0;
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT DISTINCT ON (step) step, attempt, outcome, started_at, finished_at
                FROM step_executions WHERE run_id = ? ORDER BY step, attempt DESC""", key.runId())) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("step", row.get("step"));
            step.put("status", row.get("outcome"));
            step.put("attempts", row.get("attempt"));
            step.put("startedAt", instant(row.get("started_at")));
            step.put("finishedAt", instant(row.get("finished_at")));
            steps.add(step);
            if (row.get("step").toString().startsWith("scoring-partition-") && "COMPLETED".equals(row.get("outcome"))) {
                partitionsDone++;
            }
        }
        steps.sort(Comparator.comparingInt(s -> stepOrder(s.get("step").toString())));

        Instant started = first == null ? null : utc(first.getStartTime());
        Instant finished = last != null && last.getStatus() == BatchStatus.COMPLETED ? utc(last.getEndTime()) : null;
        Duration lateness = sla.lateness(key.businessDate(), finished != null ? finished : Instant.now());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", key.runId());
        body.put("businessDate", key.businessDate().toString());
        body.put("runNumber", key.runNumber());
        body.put("state", last == null ? "SUBMITTED" : last.getStatus().name());
        body.put("executionId", last == null ? null : last.getId());
        body.put("executions", executions.size());
        body.put("startedAt", started == null ? null : started.toString());
        body.put("finishedAt", finished == null ? null : finished.toString());
        body.put("steps", steps);
        Integer total = run.get().partitionsTotal();
        body.put("partitions", Map.of("done", partitionsDone, "total", total == null ? 0 : total));
        Map<String, Object> slaBody = new LinkedHashMap<>();
        slaBody.put("deadline", sla.deadline(key.businessDate()).toString());
        slaBody.put("late", lateness.isPositive());
        slaBody.put("latenessSeconds", lateness.toSeconds());
        slaBody.put("basis", finished != null ? "finishedAt" : "now");
        body.put("sla", slaBody);
        body.put("inputHash", run.get().inputHash());
        return Optional.of(body);
    }

    private static int stepOrder(String step) {
        if (step.startsWith("scoring-partition-")) {
            return 100 + Integer.parseInt(step.substring("scoring-partition-".length()));
        }
        return switch (step) {
            case "watcher" -> 0;
            case "extract" -> 1;
            case "join" -> 1000;
            case "aggregate" -> 1001;
            case "report" -> 1002;
            case "deliver" -> 1003;
            default -> 2000;
        };
    }

    private static Instant utc(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    private static String instant(Object timestamp) {
        if (timestamp == null) {
            return null;
        }
        if (timestamp instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant().toString();
        }
        if (timestamp instanceof java.sql.Timestamp ts) {
            return ts.toInstant().toString();
        }
        return timestamp.toString();
    }
}
