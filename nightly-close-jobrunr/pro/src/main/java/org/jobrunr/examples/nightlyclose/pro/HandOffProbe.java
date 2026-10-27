package org.jobrunr.examples.nightlyclose.pro;

import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.states.JobState;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.scheduling.JobBuilder;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.storage.StorageProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.jobrunr.scheduling.JobBuilder.aBatchJob;
import static org.jobrunr.scheduling.JobBuilder.aJob;

/**
 * The chain's two hand-offs without the chain: a batch job that creates N no-op children, and a continuation
 * ({@code runAfterSuccessOf}) on the batch. No positions file, no business code. One JVM only: the timeline is kept
 * in memory and completed with the state history JobRunr stores for the batch and the continuation.
 * <pre>
 *   curl -X POST 'localhost:8182/probe/batch?children=20&amp;rateLimited=true'
 *   curl 'localhost:8182/probe/batch/{probeId}'
 * </pre>
 */
@RestController
@RequestMapping("/probe/batch")
public class HandOffProbe {

    private final JobScheduler jobScheduler;
    private final StorageProvider storageProvider;
    private final Map<UUID, Timeline> timelines = new ConcurrentHashMap<>();

    public HandOffProbe(JobScheduler jobScheduler, StorageProvider storageProvider) {
        this.jobScheduler = jobScheduler;
        this.storageProvider = storageProvider;
    }

    @PostMapping
    public Map<String, Object> start(@RequestParam(defaultValue = "20") int children,
                                     @RequestParam(defaultValue = "true") boolean rateLimited,
                                     @RequestParam(defaultValue = "0") long childMillis) {
        UUID probeId = UUID.randomUUID();
        UUID continuationId = UUID.randomUUID();
        timelines.put(probeId, new Timeline(Instant.now(), children, continuationId));
        jobScheduler.create(Stream.of(
                aBatchJob().withId(probeId).withName("Probe " + probeId + ": " + children + " children")
                        .<HandOffProbe>withJobLambda(probe -> probe.fanOut(probeId, children, rateLimited, childMillis)),
                aJob().withId(continuationId).withName("Probe " + probeId + ": continuation")
                        .runAfterSuccessOf(probeId)
                        .<HandOffProbe>withJobLambda(probe -> probe.continuation(probeId))));
        return Map.of("probeId", probeId, "children", children, "rateLimited", rateLimited, "childMillis", childMillis);
    }

    /** The batch method: creates the children in one bulk insert, then returns. Children start after this returns. */
    public void fanOut(UUID probeId, int children, boolean rateLimited, long childMillis) {
        Timeline timeline = timeline(probeId);
        timeline.batchStarted = Instant.now();
        jobScheduler.create(IntStream.rangeClosed(1, children).mapToObj(n -> {
            JobBuilder child = aJob().withName("Probe " + probeId + ": child " + n)
                    .<HandOffProbe>withJobLambda(probe -> probe.child(probeId, childMillis));
            return rateLimited ? child.withRateLimiter(ProChainLauncher.DATABASE_HEAVY) : child;
        }));
        timeline.batchReturned = Instant.now();
    }

    public void child(UUID probeId, long childMillis) throws InterruptedException {
        Timeline timeline = timeline(probeId);
        timeline.childStarts.add(Instant.now());
        if (childMillis > 0) Thread.sleep(childMillis);
        timeline.childEnds.add(Instant.now());
    }

    public void continuation(UUID probeId) {
        timeline(probeId).continuationStarted = Instant.now();
    }

    @GetMapping("/{probeId}")
    public ResponseEntity<Map<String, Object>> report(@PathVariable UUID probeId) {
        Timeline t = timelines.get(probeId);
        if (t == null) return ResponseEntity.notFound().build();
        Instant firstChildStart = t.childStarts.stream().min(Instant::compareTo).orElse(null);
        Instant lastChildStart = t.childStarts.stream().max(Instant::compareTo).orElse(null);
        Instant lastChildEnd = t.childEnds.size() == t.children ? t.childEnds.stream().max(Instant::compareTo).orElse(null) : null;
        Instant batchSucceeded = stateAt(probeId, StateName.SUCCEEDED);
        Instant continuationEnqueued = stateAt(t.continuationId, StateName.ENQUEUED);

        var body = new LinkedHashMap<String, Object>();
        body.put("probeId", probeId);
        body.put("children", t.children);
        body.put("childrenStarted", t.childStarts.size());
        body.put("childrenDone", t.childEnds.size());
        body.put("done", t.continuationStarted != null);
        var seconds = new LinkedHashMap<String, Object>();
        seconds.put("submitToBatchStart", between(t.created, t.batchStarted));
        seconds.put("batchMethod_createChildren", between(t.batchStarted, t.batchReturned));
        seconds.put("handOff1_batchReturnToFirstChildStart", between(t.batchReturned, firstChildStart));
        seconds.put("firstToLastChildStart", between(firstChildStart, lastChildStart));
        seconds.put("lastChildEndToBatchSucceeded", between(lastChildEnd, batchSucceeded));
        seconds.put("batchSucceededToContinuationEnqueued", between(batchSucceeded, continuationEnqueued));
        seconds.put("continuationEnqueuedToStarted", between(continuationEnqueued, t.continuationStarted));
        seconds.put("handOff2_lastChildEndToContinuationStart", between(lastChildEnd, t.continuationStarted));
        seconds.put("total_submitToContinuationStart", between(t.created, t.continuationStarted));
        body.put("seconds", seconds);
        body.put("childStartsPerSecond", perSecond(firstChildStart, t.childStarts));
        return ResponseEntity.ok(body);
    }

    private Instant stateAt(UUID jobId, StateName state) {
        try {
            Job job = storageProvider.getJobById(jobId);
            return job.getJobStates().stream().filter(s -> s.getName() == state).map(JobState::getCreatedAt).findFirst().orElse(null);
        } catch (RuntimeException notThereYet) {
            return null;
        }
    }

    /** Children started in each second after the first one started, so the rate limiter's release pattern shows. */
    private static Map<Long, Integer> perSecond(Instant first, ConcurrentLinkedQueue<Instant> starts) {
        var histogram = new TreeMap<Long, Integer>();
        if (first == null) return histogram;
        for (Instant start : List.copyOf(starts)) histogram.merge(Duration.between(first, start).toSeconds(), 1, Integer::sum);
        return histogram;
    }

    private static Double between(Instant from, Instant to) {
        return from == null || to == null ? null : Duration.between(from, to).toMillis() / 1000.0;
    }

    private Timeline timeline(UUID probeId) {
        return Optional.ofNullable(timelines.get(probeId))
                .orElseThrow(() -> new IllegalStateException("Probe " + probeId + " was started in another JVM"));
    }

    private static final class Timeline {
        final Instant created;
        final int children;
        final UUID continuationId;
        volatile Instant batchStarted, batchReturned, continuationStarted;
        final ConcurrentLinkedQueue<Instant> childStarts = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Instant> childEnds = new ConcurrentLinkedQueue<>();

        Timeline(Instant created, int children, UUID continuationId) {
            this.created = created;
            this.children = children;
            this.continuationId = continuationId;
        }
    }
}
