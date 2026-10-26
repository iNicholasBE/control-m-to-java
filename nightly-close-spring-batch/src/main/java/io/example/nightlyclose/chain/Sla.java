package io.example.nightlyclose.chain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.example.nightlyclose.ChainProperties;
import io.example.nightlyclose.RunKey;

/** The Control-M 06:00 SLA: the chain for business date D must be done by D+1 06:00 (zone configurable). */
@Component
public class Sla {

    private static final Logger log = LoggerFactory.getLogger(Sla.class);

    private final ChainProperties properties;
    private final JobRepository jobRepository;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public Sla(ChainProperties properties, JobRepository jobRepository) {
        this.properties = properties;
        this.jobRepository = jobRepository;
    }

    public Instant deadline(LocalDate businessDate) {
        return businessDate.plusDays(1).atTime(properties.sla().deadline()).atZone(properties.sla().zone()).toInstant();
    }

    /** Positive = late by that much, negative = that much time to spare. */
    public Duration lateness(LocalDate businessDate, Instant finishedOrNow) {
        return Duration.between(deadline(businessDate), finishedOrNow);
    }

    public void logOutcome(RunKey key, Instant finished) {
        Duration lateness = lateness(key.businessDate(), finished);
        if (lateness.isPositive()) {
            log.warn("SLA MISSED: {} finished {} after the {} deadline", key.runId(), lateness, deadline(key.businessDate()));
        } else {
            log.info("SLA met: {} finished {} before the {} deadline", key.runId(), lateness.negated(), deadline(key.businessDate()));
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void checkRunningChains() {
        for (JobExecution execution : jobRepository.findRunningJobExecutions(RunKey.JOB_NAME)) {
            RunKey key = RunKey.of(execution.getJobParameters());
            if (Instant.now().isAfter(deadline(key.businessDate())) && warned.add(key.runId())) {
                log.warn("SLA BREACH: {} still running past {}", key.runId(), deadline(key.businessDate()));
            }
        }
    }
}
