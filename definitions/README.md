# definitions: the reference chain in Control-M and Autosys

The "nightly close" chain that both Java implementations in this repository run, written the way a scheduler team would write it today:

- `control-m/nightly-close.json`: Control-M Automation API JSON, the format `ctm deploy` accepts and `ctm deploy jobs::get` returns.
- `autosys/nightly-close.jil`: Autosys JIL, the format `jil` loads and `autorep -J ALL -q` prints.

Field names were checked against public BMC and Broadcom sources on 2026-10-26. `control-m/SOURCES.md` and `autosys/SOURCES.md` list the URL behind every field and mark what we could not confirm ("verify against your release"). Neither file has been deployed to a live Control-M or Autosys server.

## The chain

```
WAIT_POSITIONS  file watcher: POSITIONS-<date>.csv in the inbox, not before 01:00, business days only
      |
EXTRACT_POSITIONS  load into staging, holds the POSITIONS_DATASET lock
      |
SCORE_P01 .. SCORE_P20  one job per portfolio partition, each takes 1 of the 20 DB_CONNECTIONS
      |
AGGREGATE_RISK  waits for all 20
      |
REGULATORY_REPORT  writes REPORT-<date>.csv
      |
DELIVER_REPORT  sends it; the SLA says this is done by 06:00
```

In Control-M every arrow is an event (condition): the upstream job adds it, the downstream job waits for it and deletes it. The 20-way fan-out and fan-in therefore cost 40 events, which is why the JSON is 1,600 lines. In Autosys the arrows are `condition: s(job)` expressions inside one box.

## Concept mapping

| Concept | Control-M (Automation API JSON) | Autosys (JIL) | JobRunr | Spring Batch |
|---|---|---|---|---|
| Group of jobs that runs together | `"Type": "Folder"` (or `SubFolder`) | `job_type: BOX`, `box_name:` | a job chain started by one entry job; jobs grouped by a label | one `Job` made of `Step`s |
| Run a shell or program | `"Type": "Job:Command"`, `Command` | `job_type: CMD`, `command:` | a Java method enqueued as a background job | a `Tasklet` or chunk step (your own code) |
| Ordering A then B | `eventsToAdd` on A, `eventsToWaitFor` + `eventsToDelete` on B | `condition: s(A)` on B | `continueWith` (job chains, Pro) | step order in the `JobBuilder` flow |
| Fan-out to N partitions and fan-in | N events out, N events in (AND) | `condition: s(P01) AND ... s(P20)` | a batch of N jobs with a batch continuation (Pro) | `Partitioner` + partitioned step; the next step waits for all partitions |
| Wait for a file | `"Type": "Job:FileWatcher:Create"`, `Path`, `SearchInterval`, `TimeLimit` | `job_type: FW`, `watch_file:`, `watch_interval:`, `term_run_time:` | your own code: a watcher or listener that enqueues the chain with a deterministic job id | your own code: a launcher that polls or listens, then starts the job |
| Business-day calendar with holidays | `When.RuleBasedCalendars` with a `Calendar:RuleBased` (`WeekDays` MON..FRI) and `ConfirmationCalendars` pointing at a holiday-aware regular calendar | `run_calendar:` naming an `autocal_asc` calendar | recurring job with an advanced CRON expression (Pro) plus a holiday check in code | nothing built in: your scheduler (cron, Kubernetes CronJob) plus your own holiday check |
| Time window "not before 01:00" | `When.FromTime: "0100"` | `date_conditions: y`, `start_times: "01:00"` | the CRON expression's time | the external trigger's time |
| Pool of 20 DB connections, 1 per job | `"DB_CONNECTIONS": {"Type": "Resource:Pool", "Quantity": "1"}` per job; pool size set on the server | `insert_resource` with `res_type: R`, `amount: 20`; `resources: (DB_CONNECTIONS, QUANTITY=1, FREE=A)` | a rate limiter (concurrent) of 20 on the scoring jobs (Pro) | thread pool or `gridSize` of the partitioned step, sized to the connection pool |
| Exclusive lock on a dataset | `{"Type": "Resource:Lock", "LockType": "Exclusive"}` | a renewable resource with `amount: 1` (no separate lock object) | a mutex on the extract job (Pro); a job cannot have both a mutex and a rate limiter | your own code: a database lock or ShedLock-style table |
| Retry on failure | `RerunLimit{Times,Every,Units}` + `ActionIfFailure` with `Action:Rerun` | `n_retrys:` | `@Job(retries = n)` with backoff | `faultTolerant().retry(...).retryLimit(n)` |
| Do not redo finished work on rerun | the job reruns as a whole | the job reruns as a whole | `runStepOnce` (durable steps) and a deterministic job id per business date | restart from the failed step using `JobRepository` state; your own idempotency inside a step |
| Success exit codes | defaults, or `On`/`If` output and code rules | `max_exit_success:` | method returns normally = success | `ExitStatus` |
| Order date / business date | `%%$ODATE` system variable | nothing built in: a global variable (`$${NC_BUSINESS_DATE}`) set upstream | a parameter of the job, part of the job id | a `JobParameter`; it also identifies the `JobInstance` |
| SLA deadline | `"Type": "Job:SLAManagement"` with `CompleteBy{Time: "06:00"}` | `must_complete_times: "06:00"` (alarm when missed) | nothing built in: an alert on "chain not done by 06:00" in your monitoring | nothing built in: same |
| SLA breach prediction while the chain runs | Control-M predicts from run history (Batch Impact Manager / SLA management) | nothing in JIL | nothing | nothing |
| Forecast of which jobs run on which future dates | Control-M forecast | nothing in JIL (`autocal_asc` shows calendar dates only) | nothing (the dashboard shows the next runs of recurring jobs, not a chain forecast) | nothing |
| Inventory export | `ctm deploy jobs::get`, `ctm deploy calendars::get`, older `exportdeffolder` | `autorep -J ALL -q` | not applicable | not applicable |

"Nothing" means there is no equivalent you get for free. The two "prediction" and "forecast" rows are the honest gap: if your operations team relies on them, plan to replace them with run-history dashboards before you switch off the scheduler.

## Files

| File | What |
|---|---|
| `control-m/nightly-close.json` | the chain in Automation API JSON |
| `control-m/SOURCES.md` | the URL behind every field name, with status |
| `control-m/bmc-sample-README.md` | origin and licence of BMC's first-job-flow sample, and why it is fetched rather than copied |
| `control-m/fetch-bmc-sample.sh` | downloads that sample locally (gitignored) |
| `autosys/nightly-close.jil` | the chain in JIL |
| `autosys/SOURCES.md` | the TechDocs page behind every attribute |

Both definitions are inputs to the `inventory/` tools. From `inventory/`, `python3 -m ctminv classify ../definitions/control-m/nightly-close.json` puts all 26 Control-M jobs in the `in_domain` bucket, and the same command on `../definitions/autosys/nightly-close.jil` does the same for the 25 Autosys jobs (the box is a container, not a job). The tests in `inventory/tests` parse both files.
