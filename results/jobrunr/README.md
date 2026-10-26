# Results: nightly close on JobRunr

Every number in this folder comes from the harness in `harness/`, run against the modules in `nightly-close-jobrunr/` in their current shape: OSS as one durable job, Pro as one batch workflow with a durable continuation (see `nightly-close-jobrunr/README.md`). All CSVs from earlier shapes (per-step jobs, polling join, `chain-shape` switch) were deleted before these runs. Whatever did not run is listed under "Not run".

## Machine and versions

| | |
|---|---|
| Date | 2026-10-26, between 21:08 and 22:00 UTC (times in the CSVs are UTC) |
| Hardware | Apple M4 Pro, 14 cores, 48 GB RAM |
| OS | macOS 26.6.2 |
| JDK | Azul Zulu 25.0.3, app heap `-Xmx2g` |
| Database | PostgreSQL 16.15 in Docker (Docker Desktop 27.5.1), `harness/docker-compose.yml`, host port 5440 |
| JobRunr | OSS 9.0.2, Pro 9.0.2, Spring Boot 4.0.9 |
| Data | `harness/gen_positions.py --seed 42`: 200,000 rows (1x, 113 MB) for business date 2026-10-23, 20 portfolios of uneven size |
| Config | Poll interval 5 s on both. OSS: 4 workers, 20 partitions on virtual threads behind a semaphore of 20. Pro: fixed 20 workers, concurrent rate limiter `database-heavy=20` on the child jobs, `PostgresEventTransport` (auto-selected) |
| Watcher | Recurring job every 15 s (OSS `@Recurring(interval = "PT15S")`, Pro 15-second cron on the business-day calendar) |

App and database ran on the same laptop. These numbers compare designs on one machine; they are not a capacity benchmark.

## Files

| File | Written by | One row per |
|---|---|---|
| `windows.csv` | `harness/run_chain.sh` | clean run |
| `hops.csv` | `harness/run_chain.sh` | clean run: the gap between one step's end and the next step's start, plus `listen_connections` (sessions in `pg_stat_activity` whose query starts with `LISTEN`) |
| `kill-tests.csv` | `harness/kill_test.sh` | kill test |
| `bridge.csv` | `harness/bridge_test.sh` | bridge simulator run |
| `dst.md` | `DstTest` in `nightly-close-jobrunr/oss` (older, unchanged by the reshape) | |

### windows.csv columns

`window_s` is from dropping the READY file in `bridge/inbox/` to the DONE file in `bridge/outbox/`, so it includes up to 15 s of waiting for the next watcher tick. `chain_s` is from the run's submit to its finish in `chain_runs`, which is the number to compare. The per-step columns come from `step_executions`; `scoring_s` is first partition start to last partition end, `join_gap_s` is last partition end to aggregate start.

### hops.csv columns

`extract_to_scoring_s` is extract end to first partition start (Pro: hand-off 1, batch method done to first child running). `scoring_to_aggregate_s` is last partition end to aggregate start (Pro: hand-off 2, last child done to `finish` running).

### kill-tests.csv columns

| Column | Meaning |
|---|---|
| `kill_point` | The marker the JVM was killed at (`kill -9`) |
| `killed_at_marker` | `yes` when the marker appeared and the kill was sent while the app paused there |
| `resume_s` | From starting the new JVM to the DONE file: detection of the dead job, retry back-off and the rest of the chain |
| `total_s` | From the trigger to the DONE file |
| `steps_with_more_than_one_attempt` | `step:count` for every step with more than one row in `step_executions`. The killed step itself always shows up: it never finished |
| `completed_steps_rerun` | Steps that had **finished successfully** before the kill and still ran again. The number that matters |
| `interrupted_steps` | Steps with a row whose `outcome` is NULL: the step(s) the JVM died in |
| `chain_runs_for_date` | Must be 1 |
| `deliveries_accepted` | Rows in the regulator stub's `deliveries`. Must be 1 |
| `duplicate_deliveries_rejected` | Delivery attempts the stub rejected on the idempotency key's UNIQUE index |
| `report_identical` | `REPORT-<date>-1.csv` byte-identical (`cmp`) to the report of a clean run of the same implementation |

## What ran

### 1. Clean runs, 1x, file transport (`windows.csv`, `hops.csv`)

```bash
cd harness && NO_BUILD=1 ./run_chain.sh jobrunr-oss 2026-10-23 --label run1 --save-reference
NO_BUILD=1 ./run_chain.sh jobrunr-pro 2026-10-23 --label run1 --save-reference
NO_BUILD=1 ./run_chain.sh jobrunr-oss 2026-10-23 --label run2
NO_BUILD=1 ./run_chain.sh jobrunr-pro 2026-10-23 --label run2
```

| | OSS run 1 | OSS run 2 | Pro run 1 | Pro run 2 |
|---|---|---|---|---|
| Window, READY file to DONE file | 20.9 s | 23.0 s | 21.3 s | 31.1 s |
| Chain, submit to finish | **4.8 s** | **7.0 s** | **19.2 s** | **22.4 s** |
| Extract | 3.59 s | 3.93 s | 3.27 s | 3.76 s |
| Hand-off 1: extract end to first partition start | 0.04 s | 0.03 s | **5.77 s** | **8.56 s** |
| Scoring, 20 partitions | 0.97 s | 2.77 s | 4.11 s | 4.11 s |
| Hand-off 2: last partition end to aggregate start | 0.01 s | 0.01 s | **5.89 s** | **5.89 s** |
| Aggregate + report + deliver | 0.10 s | 0.12 s | 0.10 s | 0.07 s |
| `LISTEN` sessions in `pg_stat_activity` | 0 | 0 | 1 | 1 |

All four reports have sha256 `2b6a0d7f6589334343cc6443aab010aa3d0585a3d9c2e19694f1e9332b28cf36`. The window differs from the chain time mainly by where the READY file lands in the 15 s watcher cycle.

**Instant job processing on Pro was active**: one `LISTEN` session per server, the `PostgresEventTransport` that Pro selects on Postgres. It does not remove the two hand-off waits, and this is why. A `JobEnqueuedEvent` goes out when a job becomes ENQUEUED, and then a worker picks it up within milliseconds. The two hand-offs are not enqueues by our code but state moves made by background tasks that run once per poll interval (5 s):

- Hand-off 1: the children created inside the batch method wait until the method returns. `ProcessBatchJobsTask` releases them on its next tick, and with the rate limiter `ProcessJobsAwaitingRateLimiterTask` releases them after that (5.8 s and 8.6 s: one or two ticks, depending on the phase).
- Scoring: the concurrent rate limiter released 4 partitions per second (start times 21:09:54, :55, :56, :57, :58 in run 1, four each). `ProcessJobsAwaitingRateLimiterTask.getJobsToEnqueuePerSecond` spreads the free slots over the seconds of one poll interval (`scheduleAt(runStart + n s)`, slots divided by `getPollInterval().getSeconds()`): 20 slots over 5 s is 4 per second. Read from the 9.0.2 bytecode with `javap`. Without the limiter (`nightly-close.pro.rate-limit-partitions=false`) the 20 children start in the same second; not re-measured in this round.
- Hand-off 2: the last child succeeds, `ProcessBatchJobsTask` marks the batch succeeded, `ProcessAwaitingJobsTask` moves `finish` from AWAITING to ENQUEUED and publishes the event. 5.89 s in both runs.

Each of these tasks publishes `JobEnqueuedEvent` after moving the jobs, so the event bus shortens ENQUEUED to PROCESSING, not AWAITING to ENQUEUED. `ProcessBatchJobsTask` does subscribe to `BatchJobEvent` on the bus, but only for pause and resume. At 1x this makes Pro's chain about 15 s slower than OSS's single JVM; the waits are fixed per hand-off and do not grow with the data.

### 2. Kill tests, OSS and Pro, 2 repeats each (`kill-tests.csv`)

```bash
cd harness && for kp in extract scoring-partition-3 aggregate deliver-before-call deliver-after-call-before-record; do
  for impl in jobrunr-oss jobrunr-pro; do NO_BUILD=1 ./kill_test.sh $impl $kp 2; done
done
```

| Kill point | Impl | Runs | Killed at marker | Resume (restart to DONE) | Completed steps that ran again | Steps interrupted | Deliveries accepted | Duplicate deliveries rejected | Report identical |
|---|---|---|---|---|---|---|---|---|---|
| extract | oss | 2 | yes / yes | 34.9 s / 37.6 s | none / none | extract | 1 / 1 | 0 / 0 | 2 of 2 |
| extract | pro | 2 | yes / yes | 52.8 s / 47.9 s | none / none | extract | 1 / 1 | 0 / 0 | 2 of 2 |
| scoring-partition-3 | oss | 2 | yes / yes | 35.4 s / 38.1 s | extract / extract | scoring-partition-* (20) | 1 / 1 | 0 / 0 | 2 of 2 |
| scoring-partition-3 | pro | 2 | yes / yes | 37.9 s / 42.9 s | none / none | scoring-partition-* (4) | 1 / 1 | 0 / 0 | 2 of 2 |
| aggregate | oss | 2 | yes / yes | 37.1 s / 36.9 s | extract / extract | aggregate | 1 / 1 | 0 / 0 | 2 of 2 |
| aggregate | pro | 2 | yes / yes | 27.9 s / 27.9 s | none / none | aggregate | 1 / 1 | 0 / 0 | 2 of 2 |
| deliver-before-call | oss | 2 | yes / yes | 32.8 s / 32.8 s | aggregate report / aggregate report | deliver | 1 / 1 | 0 / 0 | 2 of 2 |
| deliver-before-call | pro | 2 | yes / yes | 27.8 s / 27.8 s | none / none | deliver | 1 / 1 | 0 / 0 | 2 of 2 |
| deliver-after-call-before-record | oss | 2 | yes / yes | 36.8 s / 37.0 s | aggregate extract report / aggregate extract report | deliver | 1 / 1 | 1 / 1 | 2 of 2 |
| deliver-after-call-before-record | pro | 2 | yes / yes | 27.7 s / 27.8 s | none / none | deliver | 1 / 1 | 1 / 1 | 2 of 2 |

Every run: one `chain_runs` row, exactly one accepted delivery, and a report byte-identical to the clean run of the same implementation (20 of 20, OSS and Pro).

**Did a completed step rerun on Pro? No, in none of the 10 Pro kill tests.** OSS re-ran completed steps in 8 of its 10: `extract` after kills in scoring, aggregate and deliver-after-call; `aggregate` and `report` after kills in deliver. OSS saves the `runStepOnce` markers with the job on the poll interval, so a step that finished in the last few seconds before the `kill -9` is not marked done yet and runs again on the retry. Pro saves each marker the moment the step ends. Every step is idempotent, so the OSS reruns cost time, not correctness: the reports stayed identical and the regulator accepted one delivery.

Notes on the table:

- At `scoring-partition-3` the partitions that were running when the JVM died show up as interrupted: all 20 on OSS (they start together on virtual threads), 4 on Pro (the rate limiter had released 4 that second). That is the killed step, not a rerun. On OSS a retry of the score step skips partitions that have a `partition_progress` row; the score step itself is not a row in `step_executions`, so a rerun of a score step whose partitions were all done would not show in this column.
- At `deliver-after-call-before-record` the regulator had accepted the filing but the receipt was not recorded. The retry called again and the stub rejected the second call on the idempotency key: 1 duplicate rejected, 1 delivery accepted, on both editions. At `deliver-before-call` the call never happened, so 0 duplicates.
- Resume time is dominated by detecting the dead job (no heartbeat from the killed server) and the retry back-off, not by the chain: 33 to 38 s on OSS, 28 s on Pro once the killed step is past scoring, 38 to 53 s on Pro when the kill hits extract or a partition (the batch and its children each need their own orphan detection and one or two hand-off ticks).

### 3. Bridge, file transport (`bridge.csv`)

```bash
cd harness && for flags in "--refire-during-run --refire-after-done" "--drop-done-once"; do
  for impl in jobrunr-oss jobrunr-pro; do NO_BUILD=1 ./bridge_test.sh $impl file $flags; done
done
```

| Impl | Simulator flags | READY files sent | Run ids seen | After a lost DONE | Elapsed | Runs | Deliveries | Steps run twice |
|---|---|---|---|---|---|---|---|---|
| OSS | `--refire-during-run --refire-after-done` | 3 | 1 | n/a | 32.6 s | 1 | 1 | 0 |
| Pro | `--refire-during-run --refire-after-done` | 3 | 1 | n/a | 41.9 s | 1 | 1 | 0 |
| OSS | `--drop-done-once` | 2 | 1 | DONE file re-emitted | 42.1 s | 1 | 1 | 0 |
| Pro | `--drop-done-once` | 2 | 1 | DONE file re-emitted | 43.0 s | 1 | 1 | 0 |

A READY file re-sent during the run attached to the existing run (deterministic id), one re-sent after DONE got the DONE file again, and when the simulator threw away the first DONE file, its re-sent READY got the DONE file re-emitted without a second run. Simulator exit code 0 in all four.

## Not run

- 10x data (2,000,000 rows) windows, for either edition.
- Kill points `watcher`, `scoring-partition-17`, `join`, `report`, `recurring-trigger`; and more than 2 repeats per kill point.
- Pro with `worker-capacity-policy=self-learning` (configurable, not measured), and Pro without the rate limiter (`rate-limit-partitions=false`) in this round.
- `connections.sh` and `rolling_restart.sh` (their earlier CSVs were from the old shape and are gone).
- Bridge REST transport (`bridge_test.sh <impl> rest`).
- Multi-server Pro (two JVMs sharing the batch); everything here is one app instance.
