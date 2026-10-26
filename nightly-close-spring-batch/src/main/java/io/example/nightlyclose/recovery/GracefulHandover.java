package io.example.nightlyclose.recovery;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.example.nightlyclose.ChainProperties;
import io.example.nightlyclose.RunKey;
import io.example.nightlyclose.chain.ChainRuns;

/**
 * Your code, for rolling restarts: on a graceful shutdown, stop our running chains at the next chunk boundary
 * (JobOperator.stop), flag them for hand-over and let a surviving node restart them from where they stopped.
 */
@Component
public class GracefulHandover implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GracefulHandover.class);

    private final JobRepository jobRepository;
    private final JobOperator jobOperator;
    private final ChainRuns chainRuns;
    private final NodeIdentity node;
    private final JdbcTemplate jdbc;
    private final ChainProperties properties;
    private volatile boolean running;

    public GracefulHandover(JobRepository jobRepository, JobOperator jobOperator, ChainRuns chainRuns,
            NodeIdentity node, JdbcTemplate jdbc, ChainProperties properties) {
        this.jobRepository = jobRepository;
        this.jobOperator = jobOperator;
        this.chainRuns = chainRuns;
        this.node = node;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        for (JobExecution execution : jobRepository.findRunningJobExecutions(RunKey.JOB_NAME)) {
            RunKey key = RunKey.of(execution.getJobParameters());
            boolean mine = chainRuns.find(key).map(r -> node.nodeId().equals(r.ownerNode())).orElse(false);
            if (!mine) {
                continue;
            }
            log.info("{}: shutting down, stopping job execution {} for hand-over", key.runId(), execution.getId());
            chainRuns.requestHandover(key.runId());
            try {
                jobOperator.stop(execution);
            } catch (Exception e) {
                log.warn("{}: stop failed, a peer will recover it as orphaned", key.runId(), e);
                continue;
            }
            Instant deadline = Instant.now().plus(properties.recovery().shutdownWait());
            while (Instant.now().isBefore(deadline) && jobRepository.getJobExecution(execution.getId()).isRunning()) {
                sleepQuietly();
            }
            log.info("{}: job execution {} is now {}", key.runId(), execution.getId(),
                    jobRepository.getJobExecution(execution.getId()).getStatus());
        }
        // tell peers right away that this node is gone, instead of waiting for the heartbeat to go stale
        jdbc.update("UPDATE chain_nodes SET last_heartbeat = '-infinity' WHERE node_id = ?", node.nodeId());
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Stop before the web server, the Quartz scheduler and the executors go away. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }
}
