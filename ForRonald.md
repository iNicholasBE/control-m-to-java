# For Ronald: batch children and continuations wait for poll ticks in Pro 9.0.2

Hi Ronald. This is the follow-up to the question I asked in the team channel. Short version: in this repo the nightly close runs as one Pro batch job with child jobs and a `runAfterSuccessOf` continuation. Every hand-off in that flow waits for a background task tick, even with `PostgresEventTransport` active. At 20 children that makes Pro about 15 s slower than Spring Batch. At 10,000 children Pro is still slower, and with the rate limiter on it is about twice as slow, because the concurrent rate limiter releases children per tick and ramps up slowly.

Everything below was measured on 2026-10-26 and 2026-10-27 with JobRunr Pro 9.0.2. A two-minute reproduction that uses no-op jobs and none of our business code is in [How to reproduce](#how-to-reproduce).

## The question I asked the team

> In JobRunr Pro 9.0.2 on Postgres with PostgresEventTransport active and a 5 s poll interval, a batch's rate-limited child jobs start 6 to 10 s after the batch method returns (one tick of the batch task, then one of the rate limiter), and a runAfterSuccessOf continuation is enqueued exactly one poll interval (5 s) after the batch succeeds, about 5.9 s after its last child. Both delays are whole poll ticks, so they grow with the poll interval.
>
> Is there a configuration that makes these releases event-driven, or is this expected behaviour?

## The flow

[`ProChainLauncher`](nightly-close-jobrunr/pro/src/main/java/org/jobrunr/examples/nightlyclose/pro/ProChainLauncher.java) and [`ProChainJobs`](nightly-close-jobrunr/pro/src/main/java/org/jobrunr/examples/nightlyclose/pro/ProChainJobs.java):

```java
jobScheduler.create(Stream.of(
    aBatchJob().withId(runId).withMutex("positions-dataset")
        .withJobLambda(jobs -> jobs.extractAndFanOut(businessDate, runNumber, JobContext.Null)),
    aJob().withId(jobId(runId, "finish")).runAfterSuccessOf(runId)
        .withJobLambda(jobs -> jobs.finish(businessDate, runNumber, JobContext.Null))));

// inside extractAndFanOut: one child per portfolio, on a concurrent rate limiter of 20
jobScheduler.create(aJob().withId(...).withRateLimiter("database-heavy").withJobLambda(jobs -> jobs.score(...)));
```

Config ([`application.properties`](nightly-close-jobrunr/pro/src/main/resources/application.properties)): Postgres 16, poll interval 5 s, 20 fixed workers, `work-distribution-strategy=basic`, `jobrunr.jobs.rate-limiter.concurrent-job-rate-limiter.database-heavy=20`. Pro picks `PostgresEventTransport` on its own; we see its `LISTEN` session in `pg_stat_activity`.

## What we see

### The hand-offs, with no-op jobs

[`HandOffProbe`](nightly-close-jobrunr/pro/src/main/java/org/jobrunr/examples/nightlyclose/pro/HandOffProbe.java) creates a batch job whose method creates N children in one bulk `create(Stream)`, each child doing nothing, plus a `runAfterSuccessOf` continuation on the batch. It records when each job really started and reads the batch's SUCCEEDED and the continuation's ENQUEUED timestamps from the job history. All numbers are in seconds ([`results/jobrunr/handoff-probe.csv`](results/jobrunr/handoff-probe.csv)).

| Poll | Children | Rate limiter | Batch method returns → first child starts | First → last child start | Last child ends → batch SUCCEEDED | Batch SUCCEEDED → continuation ENQUEUED | Continuation ENQUEUED → started | **Total, submit → continuation starts** |
|---|---|---|---|---|---|---|---|---|
| 5 s | 20 | yes | 9.86 | 3.99 | 1.01 | 4.99 | 0.00 | **19.9** |
| 5 s | 20 | no | 2.94 | 0.00 | 5.00 | 4.98 | 0.01 | **13.0** |
| 15 s | 20 | yes | 24.82 | 9.01 | 6.00 | 14.98 | 0.01 | **54.9** |
| 15 s | 20 | no | 13.56 | 0.00 | 15.00 | 14.98 | 0.00 | **43.6** |
| 5 s | 10,000 | yes | 7.90 | 24.27 | 1.56 | 4.17 | 0.00 | **38.4** |
| 5 s | 10,000 | no | 3.93 | 7.95 | 2.09 | 3.62 | 0.01 | **17.9** |

No job in this table does any work, so every second in this table is JobRunr waiting. Three things stand out:

1. **The continuation always waits one full tick.** "Batch SUCCEEDED → continuation ENQUEUED" is 4.98 to 4.99 s at a 5 s poll and 14.98 s at a 15 s poll. Once it is ENQUEUED, it starts in under 10 ms, so the event transport works for the ENQUEUED → PROCESSING step but not for AWAITING → ENQUEUED.
2. **Batch completion can be a second full tick.** Without the rate limiter, "last child ends → batch SUCCEEDED" was exactly one tick too (5.00 s, 15.00 s), so last child → continuation was two ticks: 9.99 s at 5 s and 29.99 s at 15 s.
3. **The children of a batch wait for the batch task, then for the rate limiter.** Children created inside the batch method start 3 to 14 s after the method returns, and 8 to 25 s with the rate limiter.

### The same, with a little real work in every child

To rule out "it only looks like this because the jobs are empty", the probe has a second mode, `work=file`. Before the batch is submitted, it writes one file per child, `probe-files/<probeId>/<n>.txt`, containing `n`. Child `n` opens its own file, reads the number, adds 1 and writes it back (temp file plus atomic move). No two children touch the same file, so they never wait for each other. When the run is done, the probe checks that every file contains exactly `n + 1`, so this also shows that every child ran exactly once.

Total, submit → continuation starts, in seconds. Two runs of each, the no-op and file runs alternating in the same hour, 5 s poll:

| Children | Rate limiter | No-op children | File work (about 1 to 1.5 ms a child) | Files at exactly n + 1 |
|---|---|---|---|---|
| 20 | yes | 19.9 / 19.9 | 20.0 / 19.9 | 20 of 20, both runs |
| 20 | no | 13.0 / 13.2 | 13.0 / 13.0 | 20 of 20, both runs |
| 10,000 | yes | 38.4 / 38.6 | 37.8 / 37.7 | 10,000 of 10,000, both runs |
| 10,000 | no | 17.9 / 13.1 | 12.2 / 12.1 | 10,000 of 10,000, both runs |

The work makes no measurable difference, because the waits do not depend on it. The continuation still waits one tick after the batch succeeds (3.5 to 5.0 s), and the rate limiter still needs 24.3 to 24.4 s to start 10,000 children with or without work. The spread in the 10,000-without-limiter row (12 to 18 s) is the phase of the poll ticks, not the work: the no-op run is both the fastest and the slowest of that row.

### The rate limiter at 10,000 children

The concurrent rate limiter decides once per tick how many jobs to release, then spreads them evenly over the seconds of that tick (`scheduleAt(runStart + n s)` in `ProcessJobsAwaitingRateLimiterTask.getJobsToEnqueuePerSecond`). Its rebalance factor (starts at 1.1, max 3.0, at most +5 % per tick) grows that number slowly. Children started per second in the full chain run with 10,000 real children (each one scores one portfolio of about 20 positions):

```
s 0-4: 35/s   s 5-9: 109/s   s 10-14: 130/s   s 15-19: 156/s   s 20-24: 189/s
s 25-29: 232/s   s 30-34: 288/s   s 35-39: 362/s   ...   (49 s from the first child's start to the last child's end)
```

The same 10,000 children without the rate limiter, with the 20 workers as the only limit, all ran in 10 to 12 s. With no-op children (probe), the limiter took 24.3 s to start them all, against 8.0 s without it. Our children take about 5 ms each at this size (average; p90 7 ms; measured in the OSS run of the same scoring code), so 20 concurrent slots could run a few thousand children a second. The limiter starts at 35 a second, and in the probe it never got above about 500 a second.

### The whole chain against Spring Batch and OSS

The nightly close in all three implementations, on one laptop and one Postgres, clean runs. The positions file had 200,000 rows in 20 portfolios (1x), or the same 200,000 rows in 10,000 portfolios, which gives 10,000 partitions (Pro: 10,000 batch children; Spring Batch: a partitioned step with 10,000 partitions on a 20-thread executor; OSS: 10,000 virtual threads behind a semaphore of 20). Chain = submit to finish, in seconds.

| | 20 partitions | 10,000 partitions |
|---|---|---|
| Spring Batch 6.0.6 | 2.7 to 3.9 | **33.7 / 38.8** |
| JobRunr Pro 9.0.2, rate limiter on (our default) | 17.4 to 22.5 | **68.2 / 67.4** |
| JobRunr Pro 9.0.2, rate limiter off | 15.1 / 14.7 | **27.1 / 27.7** |
| JobRunr OSS 9.0.2, one durable job | 2.2 to 2.3 | **5.1 / 4.9** |

Where the time goes at 10,000 partitions:

| | Pro, rate limiter on | Pro, rate limiter off | Spring Batch |
|---|---|---|---|
| Extract | 1.3 to 1.4 | 1.2 to 1.3 | 2.0 |
| Extract done → first partition starts | 10.9 to 11.5 (3.9 s creating 10,000 children inside the batch method, then 7.6 s waiting) | 6.3 to 6.9 | 22.4 in run 2 (Spring Batch creating 10,000 `StepExecution`s) |
| Scoring, first partition start → last end | 49.3 | 10.2 to 11.8 | 13.5 to 13.9 |
| Last partition → aggregate starts | 5.7 to 5.8 | 7.5 to 9.0 | 0.03 |

Every Pro child here does real work: it scores one portfolio in one transaction (select its positions, 250 simulated scenarios per position, batch insert of the scores). The probe above is where the jobs are empty.

So at 10,000 children, the answer to "are we still slower than Spring Batch" is:

- **Yes, with the rate limiter on: 67 to 68 s against 34 to 39 s.** The fixed hand-off cost is the same as at 20 children (about 12 s). What grows is the rate limiter's ramp-up: 49 s of scoring against 10 to 12 s for the same children without it.
- **No, with the rate limiter off: 27 to 28 s against 34 to 39 s.** Spring Batch pays its own fixed cost at this size (22 s to persist 10,000 step executions before any partition runs), and the two Pro hand-off ticks (about 14 to 16 s together) are smaller than that.
- OSS, which runs the partitions as threads inside one job, has no hand-offs at all and stays around 5 s.

## Where the waits come from

Read from the 9.0.2 bytecode with `javap`. Please correct me if I have this wrong.

| Hand-off | Moved by | Runs |
|---|---|---|
| Children created in the batch method → released once the method has returned | `ProcessBatchJobsTask.moveAwaitingChildJobsToNextState` | Once per poll interval |
| Rate-limited child AWAITING → ENQUEUED | `ProcessJobsAwaitingRateLimiterTask`, spread over the seconds of one tick by `getJobsToEnqueuePerSecond`, amount from `ConcurrentJobRateLimiter.rebalance` | Once per poll interval |
| All children SUCCEEDED → batch SUCCEEDED | `ProcessBatchJobsTask.updateBatchJobsWithChildStats` | Once per poll interval |
| Batch SUCCEEDED → continuation AWAITING → ENQUEUED | `ProcessAwaitingJobsTask` | Once per poll interval |
| ENQUEUED → PROCESSING | Worker, woken by `JobEnqueuedEvent` over `PostgresEventTransport` | Immediately (0.00 to 0.01 s above) |

Each task publishes a `JobEnqueuedEvent` after it has moved the jobs, so ENQUEUED to PROCESSING is instant. Nothing triggers the tasks themselves early. `ProcessBatchJobsTask` subscribes to `BatchJobEvent` on the event bus, but only for pause and resume. On SQL storage the poll interval cannot go below 5 s, so 5 s per hand-off is the floor.

What we tried (`results/jobrunr/hops.csv`): rate limiter off at 20 partitions takes hand-off 1 from 6 to 11 s down to 3.5 to 3.7 s, but hand-off 2 becomes 8.9 s (batch completion and continuation release on different ticks), chain 14.7 to 15.1 s. `work-distribution-strategy=prefetch-queue` changed neither hand-off. Poll 15 s took both hand-offs to 20.9 s each.

## Questions

1. Is there a setting in 9.0.2 that makes these moves event-driven, for example running `ProcessBatchJobsTask` / `ProcessAwaitingJobsTask` when a child or parent job succeeds? Or is a tick per hand-off the expected behaviour?
2. If it is expected: is event-driven release of batch children and continuations on the roadmap? With `PostgresEventTransport` already there, the trigger seems to exist. For this chain at 20 partitions, the hand-offs are about 12 s of a 17 to 22 s run; Spring Batch does the whole chain in 3 to 4 s. That comparison is exactly what a team leaving Control-M will make.
3. Could the concurrent rate limiter hand a freed slot to the next waiting job when a job finishes, instead of planning per tick? At the very least, could the first tick start with all free slots? With 10,000 short jobs on a limit of 20, the ramp from 35/s takes most of a minute.
4. Is our shape wrong? Should a fan-out of 10,000 partitions in Pro be something other than one batch with 10,000 children?

## How to reproduce

You need Java 25, Maven 3.9, Docker, the Pro Maven repository login and your own Pro licence (the licence is never in the repo; `.gitignore` blocks `*.license`).

```bash
git clone git@github.com:iNicholasBE/control-m-to-java-for-ronald.git && cd control-m-to-java-for-ronald
export JAVA_HOME=$(/usr/libexec/java_home -v 25)       # any JDK 25
export JOBRUNR_REPO_USER=... JOBRUNR_REPO_PASSWORD=...  # Pro Maven repository
export JOBRUNR_PRO_LICENSE="$(cat /path/to/your/jobrunr-pro.license)"
cd harness && docker compose up -d --wait               # Postgres 16 on localhost:5440
```

### 1. Minimal: the probe (about 2 minutes, no business code)

```bash
# builds the Pro module, starts it on port 8282 against database nightly_jobrunr_probe, runs 4 probes
./handoff_probe.sh 20 true 20 false 10000 true 10000 false

# the same, with a little real work per child: each reads its own file, adds 1, writes it back
NO_BUILD=1 PROBE_WORK=file ./handoff_probe.sh 20 true 20 false 10000 true 10000 false

# the same with a 15 s poll interval: every tick-bound wait triples
NO_BUILD=1 ./handoff_probe.sh 20 true 20 false -- --jobrunr.background-job-server.poll-interval-in-seconds=15
```

Each probe prints its timeline as JSON (including `childStartsPerSecond`, the rate limiter's release pattern) and appends a row to `results/jobrunr/handoff-probe.csv`. You can also drive it yourself against a running Pro module:

```bash
curl -X POST 'localhost:8282/probe/batch?children=20&rateLimited=true&work=file'   # work=none|file, childMillis=N adds a sleep
curl 'localhost:8282/probe/batch/<probeId>'
```

### 2. The real chain at 10,000 partitions

`--portfolios 10000` generates 200,000 positions in 10,000 portfolios: one batch child (Pro) or partition (Spring Batch, OSS) per portfolio.

```bash
# JobRunr Pro, rate limiter on (default) and off
NO_BUILD=1 ./run_chain.sh jobrunr-pro 2026-10-23 --portfolios 10000 --label "10k rate-limited"
NO_BUILD=1 ./run_chain.sh jobrunr-pro 2026-10-23 --portfolios 10000 --label "10k no limiter" -- --nightly-close.pro.rate-limit-partitions=false

# JobRunr OSS
./run_chain.sh jobrunr-oss 2026-10-23 --portfolios 10000 --label "10k"

# Spring Batch, same Postgres
cd ../nightly-close-spring-batch && mvn -q -DskipTests package && cd scripts
PG_CONTAINER=control-m-to-java-postgres-1 PG_USER=nightly PG_PASSWORD=nightly PG_PORT=5440 \
  python3 window.py --rows 200000 --portfolios 10000 --repeats 2 --out ../../results/spring-batch/window-10000-partitions.csv
```

The JobRunr runs append to `results/jobrunr/windows.csv` (`chain_s`, `scoring_s`, `join_gap_s`) and `results/jobrunr/hops.csv` (`extract_to_scoring_s` is hand-off 1, `scoring_to_aggregate_s` is hand-off 2). To see a run's job history: dashboard on port 8102 while the app runs (port minus 180), or `jobrunr_jobs.jobasjson -> 'jobHistory'` in the database. The harness drops and recreates its database on every run. If something else is already using the default database or port, set `JOBRUNR_DB=...` and `IMPL_PORT=...`.

## Machine

Apple M4 Pro, 14 cores, 48 GB, macOS 26.6, Azul Zulu 25.0.3 (`-Xmx2g` for JobRunr, `-Xmx4g` for Spring Batch), PostgreSQL 16.15 in Docker on the same laptop. JobRunr Pro and OSS 9.0.2, Spring Boot 4.0.9, Spring Batch 6.0.6. One app instance, no cluster. These numbers compare designs on one machine. They are not a capacity benchmark. Every run and its command is in `results/`.

Nicholas
