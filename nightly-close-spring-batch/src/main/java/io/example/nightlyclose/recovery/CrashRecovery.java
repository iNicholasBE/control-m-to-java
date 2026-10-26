package io.example.nightlyclose.recovery;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.example.nightlyclose.ChainProperties;
import io.example.nightlyclose.RunKey;
import io.example.nightlyclose.chain.ChainRuns;

/**
 * Your code: after kill -9 the JobExecution stays STARTED in BATCH_JOB_EXECUTION forever, and Spring Batch refuses
 * to restart a "running" job. This component finds executions whose owner is dead (our previous incarnation, or
 * a node whose heartbeat is stale), marks them FAILED with JobOperator.recover and restarts them with
 * JobOperator.restart. It also picks up executions another node stopped gracefully and handed over.
 */
@Component
public class CrashRecovery implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CrashRecovery.class);

    private final JobRepository jobRepository;
    private final JobOperator jobOperator;
    private final ChainRuns chainRuns;
    private final NodeIdentity node;
    private final JdbcTemplate jdbc;
    private final ChainProperties properties;

    public CrashRecovery(JobRepository jobRepository, JobOperator jobOperator, ChainRuns chainRuns, NodeIdentity node,
            JdbcTemplate jdbc, ChainProperties properties) {
        this.jobRepository = jobRepository;
        this.jobOperator = jobOperator;
        this.chainRuns = chainRuns;
        this.node = node;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    private volatile boolean running;

    /**
     * Runs before the web server opens its port, so the first GET after a crash already sees the restarted
     * execution instead of the stale STARTED one.
     */
    @Override
    public void start() {
        running = true;
        sweep();
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 1000;
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public synchronized void sweep() {
        if (!properties.recovery().enabled()) {
            return;
        }
        for (JobExecution running : jobRepository.findRunningJobExecutions(RunKey.JOB_NAME)) {
            RunKey key = RunKey.of(running.getJobParameters());
            String owner = chainRuns.find(key).map(ChainRuns.ChainRun::ownerNode).orElse(null);
            if (node.nodeId().equals(owner) || !ownerIsDead(owner, running)) {
                continue;
            }
            if (chainRuns.takeOver(key.runId(), owner, node.nodeId())) {
                recoverAndRestart(key, running.getId(), "owner " + owner + " is gone");
            }
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT run_id, owner_node FROM chain_runs WHERE handover")) {
            String runId = (String) row.get("run_id");
            String owner = (String) row.get("owner_node");
            Long executionId = jdbc.queryForObject("SELECT job_execution_id FROM chain_runs WHERE run_id = ?", Long.class, runId);
            JobExecution last = executionId == null ? null : jobRepository.getJobExecution(executionId);
            if (last != null && last.getStatus() == BatchStatus.STOPPED && chainRuns.takeOver(runId, owner, node.nodeId())) {
                log.info("{}: taking over execution {} handed over by {}", runId, last.getId(), owner);
                restart(last);
            }
        }
    }

    private boolean ownerIsDead(String owner, JobExecution running) {
        Duration staleAfter = properties.recovery().staleAfter();
        if (owner == null) {
            // crashed between "execution created" and "owner recorded"; give a live launcher time to get there
            LocalDateTime created = running.getCreateTime();
            return created.toInstant(ZoneOffset.UTC).plus(staleAfter).isBefore(java.time.Instant.now());
        }
        List<Map<String, Object>> nodes = jdbc.queryForList("""
                SELECT node_name, clock_timestamp() - last_heartbeat > make_interval(secs => ?) AS stale
                FROM chain_nodes WHERE node_id = ?""", staleAfter.toSeconds(), owner);
        if (nodes.isEmpty()) {
            return true;
        }
        boolean myPreviousLife = node.nodeName().equals(nodes.getFirst().get("node_name"));
        return myPreviousLife || Boolean.TRUE.equals(nodes.getFirst().get("stale"));
    }

    private void recoverAndRestart(RunKey key, long executionId, String reason) {
        JobExecution execution = jobRepository.getJobExecution(executionId);
        log.warn("{}: job execution {} is orphaned ({}); marking FAILED and restarting", key.runId(), executionId, reason);
        jdbc.update("UPDATE step_executions SET outcome = 'INTERRUPTED' WHERE run_id = ? AND outcome = 'RUNNING'",
                key.runId());
        JobExecution recovered = jobOperator.recover(execution);
        restart(recovered);
    }

    private void restart(JobExecution execution) {
        try {
            JobExecution restarted = jobOperator.restart(execution);
            log.info("{}: restarted as job execution {}", RunKey.of(execution.getJobParameters()).runId(), restarted.getId());
        } catch (Exception e) {
            log.error("Restart of job execution {} failed", execution.getId(), e);
        }
    }
}
