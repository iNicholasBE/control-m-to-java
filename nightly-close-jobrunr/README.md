# Nightly close on JobRunr

The reference batch chain "nightly close" (chain A), as a Control-M flow would define it, rebuilt on JobRunr 9.0.2 with Spring Boot 4.0 and Postgres. Two implementations of the same chain:

| Module | What it is |
|---|---|
| `core/` | The six steps, the REST contract, the bookkeeping tables and the regulator stub. No scheduler code. |
| `oss/` | JobRunr OSS 9.0.2: durable jobs with `runStepOnce`, a hand-written fan-out and join. |
| `pro/` | JobRunr Pro 9.0.2: job chain, batch for fan-out and join, mutex, rate limiter, business-day schedule, self-learning workers. |

`core` exists so that OSS and Pro run byte-for-byte the same business code. Everything that differs between the two is in `oss/` (about 100 lines) or `pro/` (about 210 lines).

## The chain

| # | Control-M job | What it does here |
|---|---|---|
| 1 | File watcher | Waits for `inbox/POSITIONS-<businessDate>.csv` to be complete (size unchanged for 1 s) and pins its sha256 to the run. |
| 2 | Extract | `COPY` the file into `positions_staging`, in one transaction with the delete of a previous attempt. |
| 3 | Risk scoring | One job per portfolio (20 by default). Each partition writes its scores and its `partition_progress` row in one transaction. |
| | Join | Wait for all partitions. |
| 4 | Aggregate | Per-portfolio sums into `portfolio_aggregates`. |
| 5 | Regulatory report | Writes `outbox/REPORT-<businessDate>-<runNumber>.csv` (temp file plus atomic move). Content depends only on data, so a resumed run must produce a byte-identical file. |
| 6 | Deliver | POSTs the report to the regulator stub with `Idempotency-Key: NIGHTLY-CLOSE-<businessDate>-<runNumber>`, then records the receipt. |

Business days are Monday to Friday minus the dates in `holidays.txt`. The Control-M resource pool of 20 database connections becomes 20 fixed workers (OSS) or a rate limiter of 20 (Pro). The 06:00 SLA becomes a check at the end of the run that logs `SLA MISSED` with the lateness, and the lateness is in the status JSON.

## Run it

You need Java 25, Maven 3.9 and Docker.

```bash
# Postgres 16 on localhost:5440 with the databases nightly_jobrunr and nightly_springbatch
docker compose -f ../harness/docker-compose.yml up -d --wait

# A positions file for a business date (200,000 rows, about 113 MB)
mkdir -p work/inbox && python3 ../harness/gen_positions.py --date 2026-10-23 --out-dir work/inbox
cp ../harness/holidays.txt work/
```

### OSS

```bash
mvn -B package -pl oss -am
cd work && java -jar ../oss/target/nightly-close-jobrunr-oss.jar
# in another shell
curl -X POST 'localhost:8181/chains/nightly-close?businessDate=2026-10-23'
curl 'localhost:8181/chains/nightly-close/2026-10-23'
```

Dashboard: http://localhost:8001. The app reads and writes `inbox/`, `outbox/`, `markers/` and `bridge/` relative to its working directory.

### Pro

The Pro module needs a JobRunr Pro trial: request one at https://www.jobrunr.io/en/try-jobrunr-pro/ and export the two variables. The OSS module runs without it.

```bash
export JOBRUNR_REPO_USER=...        # JobRunr Pro Maven repository
export JOBRUNR_REPO_PASSWORD=...
export JOBRUNR_PRO_LICENSE="$(cat /path/to/jobrunr-pro.license)"   # the licence key that comes with the trial

mvn -B -s .mvn/jobrunr-pro-settings.xml -Ppro package -pl pro -am
cd work && java -jar ../pro/target/nightly-close-jobrunr-pro.jar
curl -X POST 'localhost:8182/chains/nightly-close?businessDate=2026-10-23'
```

`.mvn/jobrunr-pro-settings.xml` holds no secrets: it maps the `jobrunr-pro` repository to the two environment variables. Dashboard: http://localhost:8002.

## The enqueue contract

The REST endpoint, the bridge file watcher and the recurring trigger all go through one service, `EnqueueContract`. The Spring Batch implementation in this repository honours the same contract, so the harness runs against both.

**Identity.** The run id is `UUID.nameUUIDFromBytes("nightly-close|<businessDate>|<runNumber>")`. Jobs that belong to a run get ids derived from the run id (`nameUUIDFromBytes("<runId>|<part>")`), so re-creating a job is a no-op: JobRunr ignores a job whose id it already has.

**Parameters.** A run's parameters are the business date, the run number and the sha256 of the positions file. The hash is taken at submit time when the file is already there, otherwise by the file watcher step.

`POST /chains/nightly-close?businessDate=YYYY-MM-DD&runNumber=N` (`runNumber` defaults to 1)

| Situation | Status | Body |
|---|---|---|
| New run | `202 Accepted` | `{"outcome":"CREATED","runId":...,"state":"LAUNCHED"}` |
| Same id, same parameters | `200 OK` | `{"outcome":"EXISTING",...}` with the existing run; nothing new starts |
| Same id, different input file | `409 Conflict` | `{"outcome":"CONFLICT","runId":<existing>,"reason":"...Submit runNumber N+1 for an explicit rerun."}` |
| A new `runNumber` | `202 Accepted` | An explicit rerun with its own id |
| Not a business day, or `runNumber < 1` | `400 Bad Request` | `{"outcome":"REJECTED","reason":...}` |

`GET /chains/nightly-close/{businessDate}?runNumber=N` returns `404` for an unknown run, else:

```json
{
  "runId": "130b9c4d-32be-3e02-ad1a-fa552d3a75c0", "businessDate": "2026-10-23", "runNumber": 1,
  "state": "DONE", "submittedAt": "...", "startedAt": "...", "finishedAt": "...",
  "partitions": { "done": 20, "total": 20 },
  "steps": [ { "step": "watcher", "status": "SUCCEEDED", "attempts": 1, "startedAt": "...", "finishedAt": "..." }, ... ],
  "sla": { "deadline": "2026-10-24T04:00:00Z", "late": true, "latenessSeconds": 220929 },
  "reportFile": "outbox/REPORT-2026-10-23-1.csv", "reportSha256": "...", "deliveryReceipt": "RCPT-1"
}
```

Run states: `SUBMITTED` (row exists, jobs not created yet), `LAUNCHED`, `RUNNING`, `SCORING`, `JOINED`, `AGGREGATING`, `DONE`. Steps in order: `watcher`, `extract`, `scoring` (all partitions folded together), `join`, `aggregate`, `report`, `deliver`. A step's status is `PENDING`, `RUNNING`, `SUCCEEDED` or `FAILED`. The SLA deadline is 06:00 Europe/Brussels on the calendar day after the business date (`nightly-close.sla-time`, `nightly-close.sla-zone`).

A crash between inserting the run row and creating the jobs leaves the run in `SUBMITTED`. The contract launches a `SUBMITTED` run again on the next submit; deterministic job ids make that safe.

**Bridge, file transport.** A recurring job scans `bridge/inbox/` every 5 seconds for `NIGHTLY-CLOSE-<businessDate>-READY`. An optional `runNumber=N` line in the file asks for a rerun. The file goes through the contract and moves to `bridge/inbox/accepted/` or `bridge/inbox/rejected/` (a 409 or 400 also writes `bridge/outbox/NIGHTLY-CLOSE-<businessDate>-REJECTED` with the reason). When the run is done the chain writes `bridge/outbox/NIGHTLY-CLOSE-<businessDate>-DONE` with the run id, run number, report sha256 and delivery receipt. Dropping the same READY file twice is harmless.

**Bridge, REST transport.** The POST above.

### Bookkeeping the harness reads

| Table | Written by | Purpose |
|---|---|---|
| `chain_runs` | contract and steps | One row per run, the contract's source of truth |
| `step_executions(run_id, step, attempt, started_at, finished_at, outcome)` | every step, on entry and exit, outside the step's own transaction | A row with `outcome` NULL is a step the JVM died in. More than one row per step is a step that ran more than once |
| `partition_progress` | each scoring partition, in its own transaction | Partitions done; the OSS join |
| `deliveries(idempotency_key UNIQUE, ...)` | the regulator stub | Accepted deliveries |
| `delivery_rejections` | the regulator stub | Every duplicate delivery attempt (the rejected insert) |

**Markers.** On entering a step the app writes an empty `markers/<step>.entered` (`nightly-close.markers-dir`). Partitions write `scoring-partition-<n>.entered`. Extra markers: `join`, `deliver-before-call`, `deliver-after-call-before-record`, `recurring-trigger`.

**Test hook.** `nightly-close.test.pause-at=<marker>` makes the thread sleep `nightly-close.test.pause-duration` (default 3 s) right after writing that marker, so the harness's `kill -9` lands exactly there. `deliver-after-call-before-record` is the 2-second window between the regulator call and the receipt write. Off by default.

## What is Pro

| Concern | OSS module | Pro module |
|---|---|---|
| Sequence | Two durable jobs, steps inside them with `runStepOnce` | **[Pro]** Job chain built with `JobBuilder.runAfterSuccessOf`, the builder form of `continueWith`, one job per Control-M job (`nightly-close.pro.chain-shape=per-step`). Or `grouped`: durable head and tail jobs around the batch |
| Fan-out | One `enqueue` per partition with deterministic ids | **[Pro]** A batch job (`aBatchJob()`): children are created atomically, and a failed fan-out deletes them before it retries |
| Join | Hand-written: `partition_progress` plus a recurring poller every 5 s | **[Pro]** The batch's continuation starts when every child succeeded |
| Durable steps after a crash | `runStepOnce` state is saved on the poll interval, so a `kill -9` can rerun a step that had just finished | `runStepOnce` and job state are saved the moment a step or job finishes |
| Positions dataset | Nothing stops two runs from loading at once | **[Pro]** Mutex `positions-dataset` on the extract |
| 20-connection resource pool | `worker-count=20` | **[Pro]** Concurrent rate limiter `database-heavy=20` on the partitions and the aggregate |
| Business-day cron | Weekday cron in UTC; holidays checked inside the trigger | **[Pro]** `BusinessDaySchedule`, a `CustomSchedule` that skips weekends and `holidays.txt`, so no job is created on a holiday. Missed runs during downtime are caught up (`withScheduleJobsSkippedDuringDowntime`) |
| Worker count | Fixed at 20 | **[Pro]** `SelfLearningWorkerCapacityPolicy` (`worker-capacity-policy=self-learning`) with the `PrefetchQueue` (`work-distribution-strategy=prefetch-queue`); both are properties |
| Launch | Insert the run, then enqueue; a crash in between is healed by the next submit | **[Pro]** Transaction plugin: the chain's jobs and the `LAUNCHED` state commit together |

Month-end chains: any Pro cron can be the base of `BusinessDaySchedule`, for example `Cron.lastBusinessDayOfTheMonth(22, 0)`.

## What the OSS join costs you

Without batches you write the join yourself. In this module that is:

- the `partition_progress` table (8 lines of DDL) and its upsert in every partition's transaction;
- a recurring poller, `OssChainJobs.joinPoller` (14 lines), and its query `findScoringRunsWithAllPartitionsDone` (8 lines);
- a state transition `SCORING -> JOINED` guarded by a conditional `UPDATE` (7 lines), because two pollers or two last partitions must not both start the tail;
- a deterministic id for the tail job, so a poll that repeats after a crash cannot start it twice;
- latency: the tail starts on the poller's next tick, up to one poll interval (5 s) after the last partition, plus up to one more for pickup. The harness reports this as `join_gap_s`.

About 40 lines and three correctness decisions. In Pro the same join is `aBatchJob()` plus `runAfterSuccessOf(scoringBatchId)`.

## Configuration

| Property | Default | |
|---|---|---|
| `nightly-close.inbox-dir`, `outbox-dir`, `markers-dir`, `bridge-inbox-dir`, `bridge-outbox-dir`, `holidays-file` | `./inbox`, `./outbox`, `./markers`, `./bridge/inbox`, `./bridge/outbox`, `./holidays.txt` | Relative to the working directory |
| `nightly-close.watcher-timeout` | `PT6H` | How long the file watcher waits |
| `nightly-close.trigger.cron` | `0 22 * * 1-5` (OSS), `0 22 * * *` on business days (Pro) | UTC. `-` disables the recurring trigger |
| `nightly-close.regulator-url` | `http://localhost:${server.port}/regulator/submissions` | |
| `nightly-close.pro.chain-shape` | `per-step` | Pro only: `per-step` or `grouped` |
| `nightly-close.test.pause-at`, `nightly-close.test.pause-duration` | off, `PT3S` | Kill-test hook |

The recurring trigger derives the business date from its own fire time in UTC, once, inside `runStepOnce("resolve-business-date")`, and passes it on as a parameter. No step reads the clock to find out which day it is closing. See `results/jobrunr/dst.md` for why the cron runs in UTC.
