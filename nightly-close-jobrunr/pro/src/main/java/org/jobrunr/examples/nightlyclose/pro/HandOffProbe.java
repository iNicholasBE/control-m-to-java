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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.jobrunr.scheduling.JobBuilder.aBatchJob;
import static org.jobrunr.scheduling.JobBuilder.aJob;

/**
 * The chain's two hand-offs without the chain: a batch job that creates N children (no-op by default), and a continuation
 * ({@code runAfterSuccessOf}) on the batch. No positions file, no business code. One JVM only: the timeline is kept
 * in memory and completed with the state history JobRunr stores for the batch and the continuation.
 * With {@code work=file} each child does a little real I/O on a file of its own (no two children share one):
 * read {@code probe-files/<probeId>/<n>.txt}, add 1, write it back. The files are written before the batch is
 * submitted, and the report checks that every file ends up at exactly one more than it started.
 * <pre>
 *   curl -X POST 'localhost:8182/probe/batch?children=20&amp;rateLimited=true&amp;work=file'
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
                                     @RequestParam(defaultValue = "0") long childMillis,
                                     @RequestParam(defaultValue = "none") String work) throws IOException {
        if (!work.equals("none") && !work.equals("file")) throw new IllegalArgumentException("work must be none or file");
        UUID probeId = UUID.randomUUID();
        UUID continuationId = UUID.randomUUID();
        Instant setupStarted = Instant.now();
        if (work.equals("file")) {
            Files.createDirectories(filesOf(probeId));
            for (int n = 1; n <= children; n++) Files.writeString(fileOf(probeId, n), Integer.toString(n));
        }
        timelines.put(probeId, new Timeline(Instant.now(), children, continuationId, work, between(setupStarted, Instant.now())));
        jobScheduler.create(Stream.of(
                aBatchJob().withId(probeId).withName("Probe " + probeId + ": " + children + " children")
                        .<HandOffProbe>withJobLambda(probe -> probe.fanOut(probeId, children, rateLimited, childMillis, work)),
                aJob().withId(continuationId).withName("Probe " + probeId + ": continuation")
                        .runAfterSuccessOf(probeId)
                        .<HandOffProbe>withJobLambda(probe -> probe.continuation(probeId))));
        return Map.of("probeId", probeId, "children", children, "rateLimited", rateLimited, "childMillis", childMillis, "work", work);
    }

    /** The batch method: creates the children in one bulk insert, then returns. Children start after this returns. */
    public void fanOut(UUID probeId, int children, boolean rateLimited, long childMillis, String work) {
        Timeline timeline = timeline(probeId);
        timeline.batchStarted = Instant.now();
        jobScheduler.create(IntStream.rangeClosed(1, children).mapToObj(n -> {
            JobBuilder child = aJob().withName("Probe " + probeId + ": child " + n)
                    .<HandOffProbe>withJobLambda(probe -> probe.child(probeId, n, childMillis, work));
            return rateLimited ? child.withRateLimiter(ProChainLauncher.DATABASE_HEAVY) : child;
        }));
        timeline.batchReturned = Instant.now();
    }

    public void child(UUID probeId, int n, long childMillis, String work) throws Exception {
        Timeline timeline = timeline(probeId);
        timeline.childStarts.add(Instant.now());
        long started = System.nanoTime();
        if (work.equals("file")) increment(fileOf(probeId, n));
        if (childMillis > 0) Thread.sleep(childMillis);
        timeline.childNanos.add(System.nanoTime() - started);
        timeline.childEnds.add(Instant.now());
    }

    /** Read the number, add 1, write it back: temp file plus atomic move, so a crash never leaves half a number. */
    private static void increment(Path file) throws IOException {
        int value = Integer.parseInt(Files.readString(file).strip());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, Integer.toString(value + 1));
        Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING);
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
        body.put("work", t.work);
        body.put("childAvgMillis", t.childNanos.isEmpty() ? null
                : Math.round(t.childNanos.stream().mapToLong(Long::longValue).average().orElse(0) / 10_000.0) / 100.0);
        if (t.work.equals("file") && t.continuationStarted != null) body.put("files", checkFiles(probeId, t.children));
        var seconds = new LinkedHashMap<String, Object>();
        seconds.put("fileSetupBeforeSubmit", t.fileSetupSeconds);
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

    /** Every child must have added exactly 1 to its own file: n became n + 1. */
    private static Map<String, Integer> checkFiles(UUID probeId, int children) {
        int incrementedOnce = 0, otherwise = 0;
        for (int n = 1; n <= children; n++) {
            try {
                if (Integer.parseInt(Files.readString(fileOf(probeId, n)).strip()) == n + 1) incrementedOnce++;
                else otherwise++;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return Map.of("incrementedOnce", incrementedOnce, "notIncrementedOnce", otherwise);
    }

    private static Path filesOf(UUID probeId) {
        return Path.of("probe-files", probeId.toString());
    }

    private static Path fileOf(UUID probeId, int n) {
        return filesOf(probeId).resolve(n + ".txt");
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
        final String work;
        final Double fileSetupSeconds;
        volatile Instant batchStarted, batchReturned, continuationStarted;
        final ConcurrentLinkedQueue<Instant> childStarts = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Instant> childEnds = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Long> childNanos = new ConcurrentLinkedQueue<>();

        Timeline(Instant created, int children, UUID continuationId, String work, Double fileSetupSeconds) {
            this.created = created;
            this.children = children;
            this.continuationId = continuationId;
            this.work = work;
            this.fileSetupSeconds = fileSetupSeconds;
        }
    }
}
