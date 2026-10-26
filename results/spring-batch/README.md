# Results: nightly close on Spring Batch + Quartz

Module: `nightly-close-spring-batch/` (Spring Boot 4.0.9, Spring Batch 6.0.6, Quartz 2.5.2, PostgreSQL JDBC
42.7.14). Everything below ran on **2026-10-26 between 17:21 and 17:51 UTC**.

## Machine and versions

| | |
|---|---|
| CPU | Apple M4 Pro (`sysctl -n machdep.cpu.brand_string`), 14 cores (`hw.ncpu`) |
| Memory | 51539607552 bytes = 48 GiB (`sysctl -n hw.memsize`) |
| OS | macOS, Darwin 25.6.0 |
| JDK | OpenJDK 25.0.3 2026-04-21 LTS, Zulu25.34+17-CA (build 25.0.3+9-LTS); app started with `-Xmx4g` |
| Postgres | Docker image `postgres:16` from `nightly-close-spring-batch/docker-compose.yml` (`max_connections=300`, `shared_buffers=512MB`, the same settings as `harness/docker-compose.yml`), host port 5434. The exact 16.x minor was not captured: the Docker daemon stopped answering at about 17:37 UTC, after the measured runs, and `select version()` could no longer be run |
| Input | `harness/gen_positions.py --seed 42`: 40 columns, 20 portfolios of uneven size, quoted `comments` column. 1x = 200,000 rows / 113 MB, 10x = 2,000,000 rows / 1,127 MB |

Measured runs used this module's own Postgres container, not the shared harness one, so the JobRunr module's
runs against the shared server could not touch these numbers. The machine itself was shared: the JobRunr
module and other work ran at the same time (load average about 5 to 6 on 14 cores). Treat the timings as
"same order of magnitude", not as a benchmark.

Common setup for every command below:

```
cd nightly-close-spring-batch
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
mvn -DskipTests package
PG_PORT=5434 docker compose up -d
```

The scripts default to that container (`PG_CONTAINER=nightly-close-springbatch-pg`, `PG_PORT=5434`, user
`postgres`). They truncate the module's tables (contract, Spring Batch and Quartz) before each run, start the
app with `java -Xmx4g -jar target/nightly-close-spring-batch-1.0.0.jar` from a work directory under
`nightly-close-spring-batch/work/`, with the cron trigger disabled except in the rolling-restart test.

## Files

### `contract-smoke.csv`

`python3 scripts/contract_smoke.py --out ../results/spring-batch/contract-smoke.csv` (1x).
One row per check of the interface contract: `check`, `expected`, `actual`, `pass`, `detail`. All 18 passed:
new identity 202; same identity while running and after completion 200; GET 200 and 404 for an unknown
runNumber; changed input hash for the same identity 409 with the execution id and reason; runNumber 2 is a
separate run with its own report and its own delivery; a POST before the file exists leaves `watcher`
RUNNING until the file arrives; the file bridge turns a READY flag into a completed chain and a DONE flag
(3.9 s); two simultaneous POSTs give one 202 and one 200 and a single JobInstance.

### `window.csv`: the batch window

```
python3 scripts/window.py --rows 200000  --repeats 3 --out ../results/spring-batch/window.csv
python3 scripts/window.py --rows 2000000 --repeats 3 --append --out ../results/spring-batch/window.csv
```

One row per clean end-to-end run (POST, wait for COMPLETED). Step times are `finished_at - started_at` from
`step_executions`; `scoring_wall_s` is first partition start to last partition finish; `partition_min_s` /
`partition_max_s` the fastest and slowest of the 20 partitions; `chain_total_s` is `END_TIME - START_TIME` of
the JobExecution; `report_sha256` shows the report is identical across repeats.

| | 1x (200k rows), 3 runs | 10x (2M rows), 3 runs |
|---|---|---|
| watcher (includes SHA-256 of the file) | 0.26 to 0.28 s | 0.68 to 0.76 s |
| extract (chunk 5,000, includes a second SHA-256) | 1.58 to 2.45 s | 16.8 to 21.1 s |
| scoring, 20 partitions in parallel | 0.69 to 0.96 s | 4.7 to 6.4 s |
| join + aggregate + report + deliver | about 0.1 s | 0.5 to 0.9 s |
| **whole chain** | **2.74 to 3.89 s** | **23.2 to 29.6 s** |

Extract dominates: one thread, 2M inserts of the raw 40-column line into a logged staging table. The first
run of each size is the slowest (cold JVM and cold Postgres cache).

### `kill-tests.csv`

`python3 scripts/kill_tests.py --rows 200000 --repeats 3 --out ../results/spring-batch/kill-tests.csv`

First a clean run produces the reference report. Then per kill point and repeat: clean database, start the
app, POST, wait for the marker file, wait `kill_offset_s`, `kill -9` the JVM, start a new JVM, wait for
COMPLETED. The deliver kill points run with both deliver test hooks at 2000 ms.

Columns: `interrupted_steps` (rows crash recovery found `RUNNING` and set to `INTERRUPTED`, i.e. where the kill
really landed); `resume_s` (new JVM spawned until the first step row written after the kill, which includes
about 1.4 s of JVM and Spring Boot start-up); `app_ready_s` (spawn until HTTP answers);
`recovered_to_completed_s` (spawn until COMPLETED); `steps_with_more_than_one_attempt` and `steps_rerun`
(from `step_executions`); `chunk_rows_read_before_kill` / `chunk_rows_read_after_restart` (`READ_COUNT` summed
over extract and the scoring partitions per JobExecution: the two add up to exactly 400,000 = 200,000 extract +
200,000 scoring when no committed chunk was read twice); `batch_job_executions`; `deliveries_accepted`,
`duplicate_deliveries_rejected`, `delivery_receipts`; `report_identical` (SHA-256 equal to the clean run);
`final_state`.

| Kill point (offset) | Where it landed | Steps run twice | Resume after spawn | Duplicates rejected | Report identical |
|---|---|---|---|---|---|
| extract (+0.8 s) | extract, after 90,000 to 95,000 committed rows | extract (resumed at the checkpoint, read only the rest) | 1.34 to 1.38 s | 0 | yes, 3/3 |
| scoring-partition-3 (+0.3 s) | all 20 partitions (they run in parallel) | 20 partitions, each resumed at its own checkpoint | 1.48 to 1.50 s | 0 | yes, 3/3 |
| aggregate (0) | aggregate | aggregate (tasklet: reruns from its start) | 1.39 to 1.51 s | 0 | yes, 3/3 |
| report (0) | report | report (reruns from its start) | 1.40 to 1.51 s | 0 | yes, 3/3 |
| deliver-before-call (0) | deliver, before the HTTP call | deliver | 1.40 to 1.67 s | 0 | yes, 3/3 |
| deliver-after-call-before-record (0) | deliver, regulator has it, no receipt | deliver: called the regulator again | 1.40 to 2.45 s | **1 per run** (3/3) | yes, 3/3 |

Every run ended COMPLETED with exactly 2 JobExecutions (the killed one, recovered to FAILED, and its restart)
and exactly one accepted delivery. Crash recovery itself takes milliseconds; nearly all of `resume_s` is the
JVM booting.

### `rolling-restart.csv`

`python3 scripts/rolling_restart.py --rows 2000000 --out ../results/spring-batch/rolling-restart.csv`

Two nodes (`node-a` on 8191, `node-b` on 8192), same database, same clustered Quartz scheduler, same work
directory (standing in for a shared file system). A cron trigger set 25 s in the future starts the chain.
One row per event: `phase`, `at_s` (seconds since the nodes were started), `detail`. What happened:

- Quartz fired once in the cluster (one `quartz` run in `chain_runs`); the chain ran on `node-a`.
- `node-b` (not the owner) was stopped with SIGTERM during extract and started again: no effect on the chain.
- `node-a` (the owner) got SIGTERM during scoring: it stopped the job at the next chunk boundary and exited
  0.8 s later. Execution 68 ended STOPPED with 695,000 of 2,000,000 scoring rows done; `node-b` restarted it as
  execution 69 about 1 s later (its sweeper runs every 10 s, so the hand-over takes 0 to 10 s), and the 20
  partitions resumed at their checkpoints: chunk reads 2,695,000 + 1,305,000 = 4,000,000, so no committed
  chunk was processed twice.
- Steps with more than one attempt: the 20 scoring partitions (first attempt on node-a, STOPPED; second on
  node-b). No other step ran twice. 1 delivery accepted, 0 duplicates rejected. The report is byte-identical
  to a clean single-node run of the same business date.

### `dst-and-misfire.md`

`mvn test` (11 tests in `src/test/java/io/example/nightlyclose/schedule/`, all passing at 17:50 UTC). The
Quartz misfire behaviour (FireAndProceed vs DoNothing, calendar, overwrite on boot) and the DST behaviour of a
local-time vs a UTC cron around 2026-10-25 and 2027-03-28, with what they mean for the business date.

## Not run

- **Contract smoke against the shared harness Postgres** (`harness/docker-compose.yml`, port 5440). Started at
  about 17:36 UTC with `PG_CONTAINER=control-m-to-java-postgres-1 PG_USER=nightly PG_PASSWORD=nightly PG_PORT=5440`;
  that server stopped accepting connections during the run and the Docker daemon then stopped responding,
  so it did not finish. The module defaults to that server (`application.yml`); every measured run above used
  the module's own container with the same image and settings.
- **Kill tests at 10x.** Only 1x was killed. At 1x the tasklet steps are tens of milliseconds, so kills
  "at" aggregate and report land at their start.
- **The shared harness's kill-test runner**: it did not exist while this ran; `scripts/kill_tests.py` follows
  the brief instead (markers, 6 kill points, 3 repeats).
- **Quartz misfire across two JVMs on the JDBC store.** Shown with RAMJobStore in a unit test (same
  `updateAfterMisfire` code path); the clustered store was exercised only for "fires once with two nodes up".
- **Catch-up of more than one missed business date** and **purging metadata and staging tables**: not
  implemented, see the module README.
- **A kill -9 of one node while another node is up** (heartbeat-based takeover after 30 s). The code path exists
  (`CrashRecovery`, stale heartbeat), but no test exercised it; the kill tests restart the same node name.
