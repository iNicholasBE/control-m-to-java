# bridge-simulator: play the mainframe side

During a migration the mainframe keeps scheduling what has not moved yet, and it starts the Java chain through a bridge: a `NIGHTLY-CLOSE-<businessDate>-READY` file (or a REST call) one way, a `NIGHTLY-CLOSE-<businessDate>-DONE` file back. `mainframe_side.py` plays the mainframe end of that bridge against either Java implementation in this repository and checks the one property that matters: **one business date, one chain run**, whatever the mainframe does.

Python 3.10+, standard library only.

```bash
# file transport (the Java side watches ./bridge/inbox and writes ./bridge/outbox)
python3 mainframe_side.py --inbox ./bridge/inbox --outbox ./bridge/outbox \
    --business-date 2026-10-27 --refire-during-run --refire-after-done

# REST transport
python3 mainframe_side.py --rest http://localhost:8080 --outbox ./bridge/outbox \
    --business-date 2026-10-27 --refire-during-run --drop-done-once
```

It fires the trigger, polls `GET /chains/nightly-close/<businessDate>` (on `--rest`, or `--status-url`, default `http://localhost:8080`), waits for DONE with `--timeout` (default 900 s), and prints a report: triggers sent, whether and when the Java side acknowledged, the distinct run ids it reported for the key, whether DONE arrived, elapsed time. `--json-report FILE` writes the same as JSON.

| Exit code | Meaning |
|---|---|
| 0 | one run for the key, completion seen |
| 1 | more than one run id for the same key |
| 2 | no completion before `--timeout` |
| 3 | the Java side never acknowledged the trigger (`--ack-timeout`, default 60 s) |

## The three failure cases

**1. Java never acknowledges.** The READY file stays in the inbox (or the POST fails) and the status endpoint knows no run. On the mainframe this looks exactly like "Java is slow", so the bridge needs a pickup timeout of its own, separate from the SLA; the simulator exits with 3 after `--ack-timeout`. Nothing is wrong with re-sending at that point, which is where the next two cases come from.

**2. Re-fire while the chain runs** (`--refire-during-run`). The mainframe's file watcher times out, an operator re-triggers, or the transfer product delivers the file twice. The same key arrives while the first run is still busy.

**3. Re-fire after completion** (`--refire-after-done`, and `--drop-done-once`). The mainframe never saw DONE (the file was lost, or picked up and deleted by the wrong job), so it sends READY again for a business date that is already finished. `--drop-done-once` deletes the first DONE and re-sends READY after `--redrive-after` seconds; the simulator then learns the outcome from the status endpoint.

## Why the deterministic job id makes 2 and 3 harmless

Both Java implementations derive the run's identity from the business date (and an explicit run number, default 1), not from the message that asked for it. JobRunr uses it as the id of the jobs it creates, so a second enqueue for the same id is answered with the job that already exists; Spring Batch uses it as the identifying job parameter, so the same `JobInstance` comes back. A duplicate trigger during the run therefore returns "existing run, still running", and one after completion returns "existing run, done". No second chain starts, no report is written twice, nothing is delivered to the regulator twice. The run id the simulator counts stays the same, and the verdict is "OK: one run for the key".

Without that, every trigger is a new run: two chains compete for the same staging tables and the regulator gets two reports. `fake_java_side.py --mode naive` shows what that looks like.

One thing the simulator makes visible: after a lost DONE, the re-sent READY is answered with the existing finished run, but neither Java implementation writes the DONE file again. The mainframe has to read completion from the status endpoint, or the Java side should re-emit DONE when a trigger arrives for a run that is already finished. Decide which before go-live.

## Trying it without Java

`fake_java_side.py` implements the same contract (inbox/outbox files, POST and GET endpoints) with a configurable chain duration:

```bash
python3 fake_java_side.py --inbox ./bridge/inbox --outbox ./bridge/outbox --port 18080 --mode deterministic &
python3 mainframe_side.py --inbox ./bridge/inbox --outbox ./bridge/outbox --status-url http://127.0.0.1:18080 \
    --business-date 2026-10-27 --refire-during-run --refire-after-done
```

Results on 2026-10-26 against the fake (4-second chain):

| fake mode | simulator flags | run ids seen | exit |
|---|---|---|---|
| deterministic | `--refire-during-run --refire-after-done` (files) | 1 | 0 |
| deterministic | same, `--rest` | 1 (POSTs answered 202 CREATED, 200 EXISTING, 200 EXISTING) | 0 |
| deterministic | `--drop-done-once` | 1, completion read from the status endpoint | 0 |
| naive | `--refire-during-run --refire-after-done` | 3 | 1 |
| deaf | none | 0 (status 404) | 3 |

These were not yet run against the two Java implementations, which were still being built when this was written.
