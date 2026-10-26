# Nightly close on JobRunr

The reference batch chain "nightly close" (chain A), as a Control-M flow would define it, rebuilt on JobRunr 9.0.2 with Spring Boot 4.0 and Postgres. Each module builds it the way we would tell a user of that edition to build it:

| Module | Shape |
|---|---|
| `core/` | The steps, the REST contract, the inbox watcher, the bookkeeping tables and the regulator stub. No scheduler code. |
| `oss/` | JobRunr OSS 9.0.2: **one durable job**. Every step is a `runStepOnce`; the 20 portfolio partitions run in parallel inside the score step. |
| `pro/` | JobRunr Pro 9.0.2: **one batch workflow, two hops**. A batch job extracts and fans out 20 rate-limited child jobs; its continuation is a durable job for aggregate, report and deliver. |

`core` exists so that OSS and Pro run byte-for-byte the same business code. What differs is in `oss/` (about 80 lines) and `pro/` (about 220 lines including the business-day schedule).

## The chain

| # | Control-M job | Here |
|---|---|---|
| 1 | File watcher | `InboxWatcher`, a recurring job every 15 seconds (OSS: `@Recurring(interval = "PT15S")`; Pro: a 15-second cron on the business-day calendar). When `inbox/POSITIONS-<businessDate>.csv` is complete (size unchanged for 1 s) it starts the chain through the enqueue contract. It is not a step inside the chain, so no worker sits waiting for a file. |
| 2 | Extract | `COPY` the file into `positions_staging`, in one transaction with the delete of a previous attempt. Refuses a file whose sha256 differs from the one the run was submitted with. |
| 3 | Risk scoring | 20 partitions, one per portfolio. Each writes its scores and its `partition_progress` row in one transaction. |
| | Join | All partitions in: the run moves to `JOINED`. |
| 4 | Aggregate | Per-portfolio sums into `portfolio_aggregates`. |
| 5 | Regulatory report | `outbox/REPORT-<businessDate>-<runNumber>.csv`, temp file plus atomic move. Content depends only on data, so a resumed run produces a byte-identical file. |
| 6 | Deliver | POST to the regulator stub with `Idempotency-Key: NIGHTLY-CLOSE-<businessDate>-<runNumber>`, then record the receipt. |

Business days are Monday to Friday minus the dates in `holidays.txt`. The Control-M resource pool of 20 database connections becomes 20 partition threads (OSS) or a rate limiter of 20 (Pro). The 06:00 SLA becomes a check at the end of the run that logs `SLA MISSED` with the lateness; the lateness is also in the status JSON.

### OSS: one durable job

```java
@Job(name = "Nightly close %0 run %1")
public void run(LocalDate businessDate, int runNumber, JobContext jobContext) {
    UUID runId = ChainIds.runId(businessDate, runNumber);
    jobContext.runStepOnce("extract", () -> steps.extract(runId));
    jobContext.runStepOnce("score", () -> steps.scoreAll(runId));
    jobContext.runStepOnce("aggregate", () -> steps.aggregate(runId));
    jobContext.runStepOnce("report", () -> steps.report(runId));
    jobContext.runStepOnce("deliver", () -> steps.deliver(runId));
    jobContext.runStepOnce("finish", () -> steps.finish(runId));
}
```

`scoreAll` runs the partitions on virtual threads, at most 20 at once, and skips partitions that already have a `partition_progress` row, so a retried score step only scores what is missing. There is no join to write: the step returns when every partition has.

OSS saves `runStepOnce` markers with the job on the poll interval. After a hard crash, a step that had finished in the last few seconds can run again. Every step is idempotent for that reason; the kill tests show when it happens.

### Pro: a batch and a durable continuation

```java
jobScheduler.create(Stream.of(
    aBatchJob().withId(runId).withMutex("positions-dataset")
        .withJobLambda(jobs -> jobs.extractAndFanOut(businessDate, runNumber, JobContext.Null)),
    aJob().withId(jobId(runId, "finish")).runAfterSuccessOf(runId)
        .withJobLambda(jobs -> jobs.finish(businessDate, runNumber, JobContext.Null))));
```

This is `startBatch(...).continueWith(...)` written with the JobBuilder. The builder gives both jobs a deterministic id, the batch gets the run id, so a re-fire attaches to the existing batch. `continueWith` gives the continuation a fresh id on every call: a second launch of the same run would attach a second `finish` to the batch.

- `extractAndFanOut` runs `runStepOnce("extract")`, then creates the 20 child jobs with the rate limiter `database-heavy` (20 concurrent). The fan-out is not a step: when the batch method fails, JobRunr deletes the children it had created before the retry, so the retry must create them again.
- `finish` runs `runStepOnce` for aggregate, report, deliver and finish. Pro saves each marker the moment the step ends.
- The transaction plugin saves both jobs in the same transaction as the run's `LAUNCHED` state.

## Run it

You need Java 25, Maven 3.9 and Docker.

```bash
docker compose -f ../harness/docker-compose.yml up -d --wait   # Postgres 16 on localhost:5440
mkdir -p work/inbox && cp ../harness/holidays.txt work/
```

### OSS

```bash
mvn -B package -pl oss -am
cd work && java -jar ../oss/target/nightly-close-jobrunr-oss.jar
# in another shell: the file arrives, the inbox watcher starts the run within 15 s
python3 ../harness/gen_positions.py --date 2026-10-23 --out-dir work/inbox
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
```

`.mvn/jobrunr-pro-settings.xml` holds no secrets: it maps the `jobrunr-pro` repository to the two environment variables. Dashboard: http://localhost:8002.

## The enqueue contract

The REST endpoint, the inbox watcher (positions files and the bridge's READY files) and the nightly cron trigger all go through one service, `EnqueueContract`. The Spring Batch implementation in this repository honours the same contract, so the harness runs against both.

**Identity.** The run id is `UUID.nameUUIDFromBytes("nightly-close|<businessDate>|<runNumber>")`. Jobs that belong to a run use the run id (the OSS job, the Pro batch) or an id derived from it (`nameUUIDFromBytes("<runId>|<part>")`). JobRunr ignores a job whose id it already has, so creating it twice is a no-op.

**Parameters.** A run's parameters are the business date, the run number and the sha256 of the positions file, taken at submit time when the file is there.

`POST /chains/nightly-close?businessDate=YYYY-MM-DD&runNumber=N` (`runNumber` defaults to 1)

| Situation | Status | Body |
|---|---|---|
| New run | `202 Accepted` | `{"outcome":"CREATED","runId":...,"state":"LAUNCHED"}`, or `"SUBMITTED"` while the positions file has not arrived |
| Same id, same parameters | `200 OK` | `{"outcome":"EXISTING",...}` with the existing run; nothing new starts |
| Same id, run already `DONE` | `200 OK` | As above, and `bridge/outbox/NIGHTLY-CLOSE-<businessDate>-DONE` is written again |
| Same id, different input file | `409 Conflict` | `{"outcome":"CONFLICT","runId":<existing>,"reason":"...Submit runNumber N+1 for an explicit rerun."}` |
| A new `runNumber` | `202 Accepted` | An explicit rerun with its own id |
| Not a business day, or `runNumber < 1` | `400 Bad Request` | `{"outcome":"REJECTED","reason":...}` |

A run is launched when its positions file is complete. A run submitted before its file arrived (by the cron trigger, a READY file or a POST) waits in `SUBMITTED`, and the inbox watcher launches it when the file lands. A crash between inserting the run and creating its jobs also leaves `SUBMITTED`; the next submit or watcher tick launches it, and the deterministic ids make that safe.

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

Run states: `SUBMITTED`, `LAUNCHED`, `RUNNING`, `SCORING`, `JOINED`, `AGGREGATING`, `DONE`. Steps: `watcher` (only when the inbox watcher started the run), `extract`, `scoring` (all partitions folded together), `join`, `aggregate`, `report`, `deliver`, each `PENDING`, `RUNNING`, `SUCCEEDED` or `FAILED`. The SLA deadline is 06:00 Europe/Brussels on the calendar day after the business date.

**Bridge, file transport.** The inbox watcher also scans `bridge/inbox/` for `NIGHTLY-CLOSE-<businessDate>-READY`; an optional `runNumber=N` line asks for a rerun. The file goes through the contract and moves to `bridge/inbox/accepted/` or `rejected/` (a 409 or 400 also writes `bridge/outbox/NIGHTLY-CLOSE-<businessDate>-REJECTED`). When the run is done the chain writes `bridge/outbox/NIGHTLY-CLOSE-<businessDate>-DONE` with the run id, run number, report sha256 and delivery receipt. A READY file or POST for a run that is already done writes the DONE file again: the mainframe re-sends READY exactly when it never saw DONE, so it gets its answer on the channel it watches. `bridge-simulator/mainframe_side.py` exercises this (`harness/bridge_test.sh`).

**Bridge, REST transport.** The POST above.

### Bookkeeping the harness reads

| Table | Written by | Purpose |
|---|---|---|
| `chain_runs` | contract and steps | One row per run, the contract's source of truth |
| `step_executions(run_id, step, attempt, started_at, finished_at, outcome)` | every step and every partition, on entry and exit, outside the step's own transaction | `outcome` NULL: the JVM died in that step. More than one row: the step ran more than once |
| `partition_progress` | each partition, in its own transaction | Partitions done; the OSS score step skips them on a retry |
| `deliveries(idempotency_key UNIQUE, ...)` | the regulator stub | Accepted deliveries |
| `delivery_rejections` | the regulator stub | Every duplicate delivery attempt (the rejected insert) |

**Markers.** Entering a step writes an empty `markers/<step>.entered` (`nightly-close.markers-dir`): `watcher`, `extract`, `scoring-partition-<n>`, `join`, `aggregate`, `report`, `deliver`, plus `deliver-before-call`, `deliver-after-call-before-record` and `recurring-trigger`.

**Test hook.** `nightly-close.test.pause-at=<marker>` makes the thread sleep `nightly-close.test.pause-duration` (default 3 s) right after writing that marker, so the harness's `kill -9` lands exactly there. Off by default.

## What is Pro

| Concern | OSS module | Pro module |
|---|---|---|
| Sequence | One job, steps with `runStepOnce` | **[Pro]** Batch job plus a continuation (`runAfterSuccessOf`, the builder form of `continueWith`), each with `runStepOnce` steps |
| Fan-out and join | 20 virtual threads inside the score step, bounded at 20; the step returns when all partitions have | **[Pro]** 20 child jobs of the batch, spread over every server in the cluster; the continuation starts when all succeeded |
| Step markers after a crash | Saved on the poll interval: a step that just finished can run again | Saved the moment the step finishes |
| Positions dataset | Nothing stops two runs from loading at once | **[Pro]** Mutex `positions-dataset` on the batch job |
| 20-connection resource pool | The partition thread bound (per JVM) | **[Pro]** Concurrent rate limiter `database-heavy=20` on the child jobs (cluster-wide) |
| Watcher and trigger calendar | Recurring jobs on a weekday cron in UTC; holidays checked in code | **[Pro]** `BusinessDaySchedule`, a `CustomSchedule` that skips weekends and `holidays.txt`; missed nightly runs during downtime are caught up |
| Worker count | Fixed (4: the chain, the watcher, the trigger) | Fixed 20 by default. **[Pro]** `worker-capacity-policy=self-learning` with `work-distribution-strategy=prefetch-queue` as properties |
| Launch | Insert the run, then enqueue; a crash in between is healed by the next submit | **[Pro]** Transaction plugin: the jobs and the `LAUNCHED` state commit together |

### Where Pro still waits for a poll

On Postgres, JobRunr Pro 9.0.2 selects the `PostgresEventTransport` (`LISTEN/NOTIFY`) on its own; the harness sees its `LISTEN` session in `pg_stat_activity`. The event fires when a job becomes ENQUEUED by the job scheduler or by the awaiting-jobs task, and a server then starts it within milliseconds. These transitions happen in background tasks that run once per poll interval (5 s, the minimum on SQL storage), not on the event bus:

- batch children released once the batch method has finished, and the batch succeeding once its children have (`ProcessBatchJobsTask`);
- a continuation moving from AWAITING to ENQUEUED (`ProcessAwaitingJobsTask`);
- rate-limited jobs released (`ProcessJobsAwaitingRateLimiterTask`).

So the chain has two waits of up to one poll each: before the children start (the batch method has returned, `ProcessBatchJobsTask` releases its children on its next tick) and before `finish` starts (batch completion, then `ProcessAwaitingJobsTask`). Each of those tasks publishes a `JobEnqueuedEvent` once it has moved the jobs, so from ENQUEUED to PROCESSING is instant; it is the AWAITING to ENQUEUED move that waits for the tick. `ProcessBatchJobsTask` subscribes to `BatchJobEvent` on the event bus, but only for pause and resume. The concurrent rate limiter starts its 20 jobs at 4 per second: `ProcessJobsAwaitingRateLimiterTask.getJobsToEnqueuePerSecond` spreads the free slots over the seconds of one poll interval (`scheduleAt(runStart + n s)`, slots divided by `getPollInterval().getSeconds()`), so 20 slots over a 5 s poll is 4 per second. Read from the 9.0.2 bytecode with `javap`, and it matches the per-partition start times in every run. With `nightly-close.pro.rate-limit-partitions=false` all 20 children start in the same second. Measured numbers are in `results/jobrunr/README.md`.

Month-end chains: any Pro cron can be the base of `BusinessDaySchedule`, for example `Cron.lastBusinessDayOfTheMonth(22, 0)`.

## Configuration

| Property | Default | |
|---|---|---|
| `nightly-close.inbox-dir`, `outbox-dir`, `markers-dir`, `bridge-inbox-dir`, `bridge-outbox-dir`, `holidays-file` | `./inbox`, `./outbox`, `./markers`, `./bridge/inbox`, `./bridge/outbox`, `./holidays.txt` | Relative to the working directory |
| `nightly-close.trigger.cron` | `0 22 * * 1-5` (OSS), `0 22 * * *` on business days (Pro) | UTC. `-` disables the nightly trigger |
| `nightly-close.regulator-url` | `http://localhost:${server.port}/regulator/submissions` | |
| `nightly-close.pro.rate-limit-partitions` | `true` | Pro only: put the child jobs on the `database-heavy` rate limiter |
| `nightly-close.test.pause-at`, `nightly-close.test.pause-duration` | off, `PT3S` | Kill-test hook |
| `jobrunr.background-job-server.poll-interval-in-seconds` | `5` | The minimum on SQL storage; it must stay below the 15 s inbox watcher (OSS: the poll interval must be shorter than the most frequent recurring job) |

The nightly trigger derives the business date from its own fire time in UTC, once, inside `runStepOnce("resolve-business-date")`, and passes it on as a parameter. No step reads the clock to find out which day it is closing. See `results/jobrunr/dst.md` for why the cron runs in UTC.
