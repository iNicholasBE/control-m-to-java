# Results: nightly close on JobRunr

Every number in this folder comes from the harness in `harness/`, run against the modules in `nightly-close-jobrunr/`. This page says what ran, on what, when, and with which command. Whatever did not run is listed under "Not run".

## Machine and versions

| | |
|---|---|
| Date | 2026-10-26 (all runs, times in the CSVs are UTC) |
| Hardware | Apple M4 Pro, 14 cores, 48 GB RAM (`sysctl -n machdep.cpu.brand_string`, `hw.ncpu`, `hw.memsize` = 51539607552) |
| OS | macOS 26.6.2 |
| JDK | Azul Zulu 25.0.3 (`25.0.3+9-LTS`), app heap `-Xmx2g` |
| Database | PostgreSQL 16.15 in Docker (Docker Desktop 27.5.1), `harness/docker-compose.yml`, host port 5440, `max_connections=300`, `shared_buffers=512MB` |
| JobRunr | OSS 9.0.2, Pro 9.0.2, Spring Boot 4.0.9 |
| Data | `harness/gen_positions.py --seed 42`: 200,000 rows (1x, 113 MB) for business date 2026-10-23, 40 columns, 20 portfolios of uneven size |

App and database ran on the same laptop. These numbers compare designs on one machine; they are not a capacity benchmark.

## Files

| File | Written by | One row per |
|---|---|---|
| `kill-tests.csv` | `harness/kill_test.sh` | kill test |
| `windows.csv` | `harness/run_chain.sh` | clean run |
| `connections.csv`, `connections-summary.csv` | `harness/connections.sh` | sample (1 s), run |
| `rolling-restart.csv` | `harness/rolling_restart.sh` | rolling restart run |
| `dst.md` | `DstTest` in `nightly-close-jobrunr/oss` | |

### kill-tests.csv columns

| Column | Meaning |
|---|---|
| `kill_point` | The marker the JVM was killed at (`kill -9`), see the module README |
| `killed_at_marker` | `yes` when the marker appeared and the kill was sent while the app paused there |
| `resume_s` | From starting the new JVM to the `DONE` file: detection of the dead job, retry back-off and the rest of the chain |
| `total_s` | From the trigger to the `DONE` file |
| `steps_with_more_than_one_attempt` | `step:count` for every step with more than one row in `step_executions`. The killed step itself always shows up here: it never finished, so it must run again |
| `completed_steps_rerun` | Steps that had **finished successfully** and still ran again. This is the number that matters |
| `interrupted_steps` | Steps with a row whose `outcome` is NULL: the step the JVM died in |
| `chain_runs_for_date` | Must be 1: the restart must not start a second run |
| `deliveries_accepted` | Rows in the regulator stub's `deliveries`. Must be 1 |
| `duplicate_deliveries_rejected` | Delivery attempts the stub rejected on the idempotency key's UNIQUE index |
| `report_identical` | `REPORT-<date>-1.csv` byte-identical (`cmp`) to the report of a clean run of the same implementation |

## What ran

Work stopped at about 18:55 CET because the host disk filled up (0.1 to 1 GB free out of 926 GB). Docker Desktop's VM stalled on writes and every `docker` command hung, Postgres included. See "Not run".

### 1. Clean run, OSS, 1x (smoke run, row not kept in `windows.csv`)

```bash
cd harness && NO_BUILD=1 ./run_chain.sh jobrunr-oss 2026-10-23 --label smoke --save-reference
```

17:25:38 UTC, file transport. Window from dropping the READY file to the DONE file: **16.1 s**. In the database (submitted to finished): 10.1 s. Per step: watcher 0.05 s, extract 1.48 s, scoring (20 partitions) 1.11 s, **join gap 7.25 s**, aggregate 0.06 s, report 0.00 s, deliver 0.03 s. The rest of the window is the bridge watcher's 5 s poll before the run is submitted. Report sha256 `2b6a0d7f6589334343cc6443aab010aa3d0585a3d9c2e19694f1e9332b28cf36`. This report is the reference for the kill tests. `windows.csv` was cleared before the kill-test batch, and the batch stopped before the clean window runs were due.

Two manual Pro runs (same data, throwaway database `nightly_pro_smoke`, while the OSS kill tests ran, so the timings are indicative only):

| Pro chain shape | Submitted to finished | Notes |
|---|---|---|
| `per-step` (default) | 41.1 s | Each of the 6 continuations started about 5 s (one poll) after its parent; scoring 4.1 s |
| `grouped` | 20.9 s | Two continuation hops; extract to first partition 11.6 s (continuation, batch start, rate limiter) |

Both produced the same report sha256 as OSS. Scoring took 4.1 s instead of 1.1 s because `SelfLearningWorkerCapacityPolicy` started 4 partitions per second (per-partition start times in the smoke database), not because of the rate limiter.

### 2. Kill tests, OSS (6 of the planned 30)

```bash
cd harness && for kp in watcher extract scoring-partition-3 scoring-partition-17 join aggregate report \
    deliver-before-call deliver-after-call-before-record recurring-trigger; do
  NO_BUILD=1 ./kill_test.sh jobrunr-oss $kp 3
done
```

The batch completed `watcher` x3 and `extract` x3 (rows in `kill-tests.csv`), then hung on `docker compose up` for `scoring-partition-3`. One earlier single run at `deliver-after-call-before-record` is described below; its row was cleared with the CSV before the batch started.

| Kill point | Runs | Resume (restart to DONE) | Completed steps that ran again | Duplicate deliveries rejected | Report identical |
|---|---|---|---|---|---|
| watcher | 3 | 42.9 / 47.8 / 47.8 s | none | 0 | 3 of 3 |
| extract | 3 | 49.1 / 44.3 / 48.1 s | `watcher` in 3 of 3 | 0 | 3 of 3 |
| deliver-after-call-before-record (single run, 17:26 UTC, not in the CSV) | 1 | 32.8 s | `aggregate`, `report` | 1 | yes |

Every run: one `chain_runs` row and one accepted delivery.

What it shows: on OSS, a `kill -9` re-runs steps that had **already finished** inside the same job. `runStepOnce` markers are saved with the job on the poll interval, not when the step ends. The JobRunr durable-execution guide documents this ("JobRunr OSS saves state on its normal poll interval ... a hard crash in the window since the last save can re-run the step that had just finished"). The idempotent steps absorb it: staging and scores are replaced in one transaction, the report is rewritten byte-identically, and the second delivery is rejected on the idempotency key. Resume time is mostly JobRunr noticing the dead server (4 x the 5 s poll interval) plus the retry back-off.

### 3. DST

```bash
cd nightly-close-jobrunr && mvn -B test -pl oss -am -Dtest=DstTest -Dsurefire.failIfNoSpecifiedTests=false
```

2 tests, both pass. Output in `dst.md`. Finding: JobRunr's own cron with a local `zoneId` does **not** double-fire or skip. On 2026-10-25 it fires once, then waits 25 h. On 2027-03-28 the missing 02:30 becomes 03:30 local, then a 23 h gap. A naive wall-clock scheduler, which the test includes as the reference, does double-fire on 25 October and skips 28 March. The UTC cron fires every 24 h at the same instant.

## Not run

All of these need Docker and roughly 4 GB of free host disk. The harness now refuses to start below `MIN_FREE_GB` (default 4) and times out hung Docker calls instead of stalling.

| Planned | Command |
|---|---|
| OSS kill tests: scoring-partition-3, scoring-partition-17, join, aggregate, report, deliver-before-call, deliver-after-call-before-record, recurring-trigger (3 each) | `./kill_test.sh jobrunr-oss <killPoint> 3` |
| All Pro kill tests (3 each) | `export JOBRUNR_PRO_LICENSE=...; ./kill_test.sh jobrunr-pro <killPoint> 3` |
| Clean windows at 1x and 10x, OSS and Pro (`per-step` and `grouped`) | `./run_chain.sh jobrunr-oss 2026-10-23 --scale 1` (and `--scale 10`); for Pro grouped add `-- --nightly-close.pro.chain-shape=grouped` |
| Connections: OSS fixed 20, Pro fixed 20 against self-learning plus prefetch queue | `./connections.sh jobrunr-oss`, `./connections.sh jobrunr-pro` |
| Rolling restart, OSS and Pro | `./rolling_restart.sh jobrunr-oss`, `./rolling_restart.sh jobrunr-pro` |

The 10x file (2,000,000 rows, about 1.1 GB) was never written: the generator was killed when the disk filled. `gen_positions.py` produced 200,000 rows in 2.7 s, so 10x takes about 30 s.

## Running the harness

```bash
cd harness
docker compose up -d --wait                 # Postgres 16 on localhost:5440
./run_chain.sh jobrunr-oss 2026-10-23 --scale 1 [--transport file|rest] [--save-reference]
./kill_test.sh jobrunr-oss extract 3
./rolling_restart.sh jobrunr-oss [--scale 10]
./connections.sh jobrunr-pro
```

`impls/<impl>.conf` tells the harness how to build and start an implementation (`jobrunr-oss`, `jobrunr-pro`, `springbatch`). `NO_BUILD=1` skips the Maven build. The app runs with `harness/work/<impl>/` as its working directory, positions files are generated once into `harness/data/` and hard-linked in. The harness only stops processes it started itself (by pid) and refuses to start when a port is taken.

