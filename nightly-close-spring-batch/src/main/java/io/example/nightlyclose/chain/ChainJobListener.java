package io.example.nightlyclose.chain;

import java.time.Instant;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

import io.example.nightlyclose.bridge.DoneFlags;
import io.example.nightlyclose.RunKey;
import io.example.nightlyclose.recovery.NodeIdentity;

/** Ownership for crash recovery, the dataset mutex, the SLA log line and the bridge DONE file. */
@Component
public class ChainJobListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ChainJobListener.class);

    private final ChainRuns chainRuns;
    private final NodeIdentity node;
    private final DatasetMutex mutex;
    private final Sla sla;
    private final DoneFlags doneFlags;

    public ChainJobListener(ChainRuns chainRuns, NodeIdentity node, DatasetMutex mutex, Sla sla,
            DoneFlags doneFlags) {
        this.chainRuns = chainRuns;
        this.node = node;
        this.mutex = mutex;
        this.sla = sla;
        this.doneFlags = doneFlags;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        RunKey key = RunKey.of(jobExecution.getJobParameters());
        chainRuns.claim(key, node.nodeId(), jobExecution.getId());
        mutex.acquire(jobExecution.getId());
        log.info("{}: job execution {} started on {}", key.runId(), jobExecution.getId(), node.nodeName());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        mutex.release(jobExecution.getId());
        RunKey key = RunKey.of(jobExecution.getJobParameters());
        log.info("{}: job execution {} ended {}", key.runId(), jobExecution.getId(), jobExecution.getStatus());
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            Instant finished = jobExecution.getEndTime() == null ? Instant.now()
                    : jobExecution.getEndTime().toInstant(ZoneOffset.UTC);
            sla.logOutcome(key, finished);
            doneFlags.write(key);
        }
    }
}
