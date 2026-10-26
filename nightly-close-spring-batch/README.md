# Nightly close (chain A) on Spring Batch + Quartz

The reference batch chain from the Control-M flow, built on Spring Boot 4.0.9, Spring Batch 6.0.6 and Quartz
2.5.2 (clustered JDBC job store) on Postgres 16. It honours the same interface contract as the JobRunr module
next to it, so one kill-test harness runs against both.

```
watcher -> extract -> scoring (20 partitions, one per portfolio) -> join -> aggregate -> report -> deliver
```

| Control-M | Here |
|---|---|
| File watcher on `POSITIONS-<businessDate>.csv` | `watcher` tasklet, one poll per `execute()` call |
| Load into staging | `extract`: chunk step, `FlatFileItemReader` -> `JdbcBatchItemWriter`, chunk size 5,000 |
| Risk scoring per portfolio | `scoring`: Spring Batch local partitioning, `TaskExecutorPartitionHandler`, one partition per portfolio |
| Fan-in | the partition step blocks until all workers are done; `join` then checks every staged row has a score |
| Aggregate, report | tasklets; the report is written to a temp file and moved atomically, no timestamps inside |
| Deliver to the regulator | `deliver` tasklet: HTTP POST to a stub inside the app with `Idempotency-Key: NIGHTLY-CLOSE-<date>-<run>` |
| Calendar "business days" | Quartz `HolidayCalendar` (dates from `holidays.txt`) on a `WeeklyCalendar` without Saturday and Sunday |
| Scheduling | Quartz cron trigger `0 30 22 * * ?` in UTC, clustered JDBC store, FireAndProceed misfire policy |
| SLA 06:00 | `Sla`: deadline = businessDate + 1 day at 06:00 (`chain.sla.zone`, default UTC); logged on completion and checked every minute while running |
| Resource pool, 20 DB connections | pool size of the partition `TaskExecutor` (your code, see below) |
| Mutex on the dataset | Postgres advisory lock held for the whole job execution (your code) |

## Run it

```
# Postgres: the shared harness (port 5440, user nightly) is the default target
docker compose -f ../harness/docker-compose.yml up -d
# or this module's own container (port 5434, user postgres), used for the measured runs
docker compose up -d

export JAVA_HOME=$(/usr/libexec/java_home -v 25)
mvn package
mkdir -p work && cp holidays.txt work/ && cd work
python3 ../../harness/gen_positions.py --date 2026-10-23 --rows 200000 --out-dir ./inbox
java -jar ../target/nightly-close-spring-batch-1.0.0.jar          # add PORT, DB_URL, DB_USER, DB_PASSWORD as needed
curl -X POST 'localhost:8080/chains/nightly-close?businessDate=2026-10-23'
curl 'localhost:8080/chains/nightly-close/2026-10-23?runNumber=1'
```

Directories are relative to the working directory and configurable: `chain.inbox`, `chain.outbox`,
`chain.markers`, `chain.bridge.inbox`, `chain.bridge.outbox`, `chain.holidays` (or the `CHAIN_*` environment
variables in `application.yml`). Two instances on one database need different `PORT`s and `--chain.node-name`s.

Measurement scripts (python3 + docker, no other dependencies) are in `scripts/`; `results/spring-batch/README.md`
lists the exact commands and what they produced.

## The enqueue contract as implemented

`POST /chains/nightly-close?businessDate=YYYY-MM-DD&runNumber=N` (runNumber defaults to 1)

The identity of a run is (businessDate, runNumber), passed as the two **identifying JobParameters**. Spring
Batch's JobInstance uniqueness (`JOB_INST_UN` on job name + hash of identifying parameters) does the
deduplication; `ChainService` maps the outcomes:

| Situation | Response |
|---|---|
| New identity | `202` with the run (state `STARTING`) |
| Same identity, an execution is running (`JobExecutionAlreadyRunningException`) | `200` with the existing run |
| Same identity, completed (`JobInstanceAlreadyCompleteException`) | `200` with the existing run |
| Same identity, last execution FAILED or STOPPED | `200`, `"restarted": true`: the same call is Spring Batch's restart (new JobExecution, COMPLETED steps skipped) |
| Same identity, but the input file in the inbox now has a different SHA-256 than the one recorded for the run | `409` with `executionId` and `reason` |
| New runNumber | an explicit rerun: a new JobInstance, its own report `REPORT-<date>-<run>.csv` and its own idempotency key |
| Two simultaneous POSTs of a new identity | one `202`, one `200` (the loser's `createJobInstance` hits the unique constraint) |

The input hash is recorded when the POST sees the file or, if the file was not there yet, when the watcher
finds it. `extract` checks it again before reading, because a restart resumes at line N of whatever file is
there now.

`GET /chains/nightly-close/{businessDate}?runNumber=N` returns `state` (the Spring Batch status of the last
JobExecution, or `SUBMITTED`), `executionId`, `executions`, `startedAt`, `finishedAt`, `steps` (per contract
step: latest `status`, `attempts`, `startedAt`, `finishedAt`), `partitions` `{done, total}`, `sla`
`{deadline, late, latenessSeconds, basis}` and `inputHash`. Unknown identity: `404`.

Contract tables (all in `db/schema-chain.sql`):

- `step_executions(run_id, step, attempt, started_at, finished_at, outcome, node, job_execution_id)`: a row on
  entry (`RUNNING`), completed on exit with the Spring Batch status. Step names: `watcher`, `extract`,
  `scoring-partition-1` to `-20` (portfolios in sorted order), `join`, `aggregate`, `report`, `deliver`.
  `run_id` is `NIGHTLY-CLOSE-<businessDate>-<runNumber>`. A row left `RUNNING` by a crash is set to
  `INTERRUPTED` by crash recovery.
- `deliveries(idempotency_key UNIQUE, business_date, run_number, delivered_at, ...)`, written by the regulator
  stub; a duplicate is a rejected insert, answered `409` and logged in `delivery_rejections`. The chain's own
  record that delivery happened is `delivery_receipts`.
- Markers: `./markers/<step>.entered` on entry of every step, plus `deliver-before-call.entered` and
  `deliver-after-call-before-record.entered` inside `deliver`.
- Test hooks: `chain.test-hooks.deliver-sleep-after-call` (the 2 s between the stub call and the receipt
  write) and `chain.test-hooks.deliver-sleep-before-call` (so a kill can land before the call), environment
  `CHAIN_DELIVER_SLEEP_AFTER_CALL=2000ms`, `CHAIN_DELIVER_SLEEP_BEFORE_CALL=2000ms`.
- File bridge: every 5 s `./bridge/inbox/NIGHTLY-CLOSE-<businessDate>-READY` is submitted through the same
  `ChainService` (runNumber 1) and moved to `./bridge/inbox/processed/`; on completion
  `./bridge/outbox/NIGHTLY-CLOSE-<businessDate>-DONE` is written.

## What Spring Batch gives you

- **Restart from the failed step.** A FAILED or STOPPED JobExecution restarted with `JobOperator.restart`
  (or started again with the same identifying parameters) skips the COMPLETED steps and reruns the rest.
- **Chunk checkpoints.** `extract` and every scoring partition save their reader position in the step
  ExecutionContext inside the chunk transaction, so a restart resumes after the last committed chunk. The kill
  tests show it: after a kill in `extract` at about 90,000 rows, the restart reads the remaining 110,000.
- **Partition restart.** On restart the partition step re-creates only the partitions that did not complete,
  each with its own saved reader position.
- **Metadata tables** (`BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`, `..._PARAMS`, `BATCH_STEP_EXECUTION`, the two
  `..._CONTEXT` tables): every execution, its parameters, per-step read/write/commit/rollback counts, status,
  exit message.
- **Stop at a chunk boundary.** `JobOperator.stop` marks the execution STOPPING; chunk steps notice at the next
  commit and end STOPPED, restartable.
- **`JobOperator.recover`** (new in 6.0): marks a STARTED execution and its running steps FAILED so it can be
  restarted.

**Honest limit:** an interrupted non-chunked step (a tasklet: `watcher`, `join`, `aggregate`, `report`,
`deliver`) reruns from its start. Each of them is written so that is safe: `aggregate` deletes and inserts in
one transaction, `report` rewrites the same bytes, `deliver` relies on the idempotency key at the regulator.

## What you have to write yourself

| Need | Here | Why Spring Batch does not cover it |
|---|---|---|
| Crash recovery | `recovery/CrashRecovery`, `NodeIdentity` | After `kill -9` the execution stays `STARTED` forever and a restart is refused as "already running". Spring Batch keeps no owner and no heartbeat, so it cannot tell "running elsewhere" from "dead". The module records the owner node per run, heartbeats every 5 s, and on boot (before the web port opens) and every 10 s recovers executions whose owner is its own previous incarnation or has a heartbeat older than 30 s: `JobOperator.recover`, then `JobOperator.restart`. A compare-and-set on `chain_runs.owner_node` stops two survivors from both restarting it |
| Hand-over on graceful shutdown | `recovery/GracefulHandover` | `JobOperator.stop` exists, nothing restarts the STOPPED execution elsewhere. On SIGTERM the node stops its chains, flags them for hand-over and marks itself gone; a peer restarts them |
| Resource pool (20 DB connections) | `partitionExecutor` pool size = `chain.resources.db-connections` | No resource pools. This caps concurrent partition workers **per JVM**; two nodes could use 40. A cluster-wide cap needs a DB-backed semaphore |
| Mutex on the dataset | `chain/DatasetMutex`: `pg_try_advisory_lock` on a connection held for the job execution | No mutual exclusion between job instances. The lock dies with the connection, so a crashed JVM never leaves it behind |
| Join check | `join` tasklet | Waiting for the workers is built in; checking the result is not |
| Markers, `step_executions` | `tracking/StepTracker` (a `StepExecutionListener` on every step) | Contract-specific |
| Business date from the trigger, misfire catch-up | `schedule/BusinessCalendar.businessDateFor` | With FireAndProceed Quartz reports the recovery moment as the scheduled fire time; see `results/spring-batch/dst-and-misfire.md`. Catching up more than one missed night is **not** implemented |
| Idempotent schema setup | `db/schema-*.sql` | The stock Quartz DDL drops all tables first; running it on each boot would wipe the stored trigger and its misfire state |

### What an operator does without the crash-recovery code

After `kill -9` mid-chain:

1. `BATCH_JOB_EXECUTION` shows the execution `STARTED` with no end time; `BATCH_STEP_EXECUTION` shows the
   interrupted step `STARTED` (for a kill during scoring: up to 21 rows, the manager step and every running
   partition).
2. `POST` again (or `JobOperator.start` with the same parameters) is refused with
   `JobExecutionAlreadyRunningException`; this module would answer `200` with a run that never moves.
3. The operator first has to make sure no other node is really still running it. Spring Batch cannot tell.
4. Then mark it failed: on Spring Batch 6 by calling `JobOperator.recover(jobExecution)` from some admin entry
   point you have to provide; before 6.0 by hand:
   `UPDATE BATCH_STEP_EXECUTION SET STATUS='FAILED', EXIT_CODE='FAILED', END_TIME=now() WHERE JOB_EXECUTION_ID=? AND STATUS IN ('STARTING','STARTED','STOPPING');`
   `UPDATE BATCH_JOB_EXECUTION SET STATUS='FAILED', EXIT_CODE='FAILED', END_TIME=now() WHERE JOB_EXECUTION_ID=?;`
5. Restart: `JobOperator.restart(jobExecution)`, or start again with the same identifying parameters.
6. If a step ended `UNKNOWN` (a failure while rolling back), Spring Batch refuses to restart it at all; that
   needs a person to decide what state the data is in.

## Retention of the Spring Batch metadata tables

Spring Batch never deletes anything. Per business day this chain adds 1 job instance, 1 or more executions,
2 parameter rows and 27 step executions with their contexts per execution (watcher, extract, the scoring
manager, 20 partitions, join, aggregate, report, deliver), so about 7,000 step rows a year: small, but
unbounded, and `findRunningJobExecutions` and the GET status read these tables. `JobRepository` has
`deleteJobInstance` / `deleteJobExecution`; a purge (for example "keep 400 days", run from the same Quartz
scheduler) is your code and is **not** in this module. Decide the retention with audit: the metadata is
the evidence of what ran when, and the regulator may ask for it longer than operations needs it.

The bigger growth is this module's own data: `positions_staging` keeps every raw input line (about 113 MB per
business day at 1x, 1.1 GB at 10x) and `risk_scores` one row per position. A real deployment purges or
partitions them by business date after delivery; not implemented here.

## Code map

```
src/main/java/io/example/nightlyclose/
  RunKey, ChainProperties, BatchInfrastructureConfig
  batch/      job and step definitions, readers, tasklets, partitioner
  chain/      REST contract, ChainService, status, SLA, dataset mutex, job listener
  recovery/   node identity + heartbeat, crash recovery, graceful hand-over
  schedule/   Quartz trigger, business-day calendar, trigger job
  bridge/     READY/DONE file bridge
  regulator/  the regulator stub
  tracking/   step_executions + markers
src/main/resources/db/  idempotent DDL for Spring Batch, Quartz and the contract tables
src/test/java/...       Quartz misfire, DST and business-calendar tests
scripts/                kill tests, window, rolling restart, contract smoke
```
