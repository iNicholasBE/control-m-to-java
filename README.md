# Moving batch off Control-M to Java: the example code

Everything behind the article [Moving batch off Control-M to Java: what you keep, what you lose, what you move first](https://www.jobrunr.io/en/blog/control-m-to-java/). One reference batch chain, written the way a scheduler team writes it today and rebuilt twice in Java, plus the tooling to inventory an estate before anyone migrates anything.

Nothing here has been deployed against a live Control-M or Autosys server. Where a field name or a command could not be verified against public documentation, the file says so.

## What is in here

| Directory | What it is | Needs |
|---|---|---|
| [`definitions/`](definitions/) | The "nightly close" chain as a Control-M Automation API JSON definition and as Autosys JIL, with a concept-by-concept mapping to the Java implementations and a source for every field name | nothing |
| [`inventory/`](inventory/) | `ctminv`: parsers for Control-M JSON and XML exports and Autosys JIL, the four-bucket classifier, the hidden time-gap dependency finder with its measured precision and recall, a synthetic 9,000-definition estate with planted truth | Python 3 |
| [`nightly-close-jobrunr/`](nightly-close-jobrunr/) | The chain in JobRunr: an open-source module (`oss`) and a Pro module (`pro`) sharing the same business code (`core`) | Java 25, Docker; the Pro module needs a trial, see below |
| [`nightly-close-spring-batch/`](nightly-close-spring-batch/) | The same chain in Spring Batch scheduled by Quartz, with the crash recovery Spring Batch does not have on its own | Java 25, Docker |
| [`harness/`](harness/) | Postgres compose file, synthetic positions generator, and the scripts that run the chain, kill it at a marker, restart it, roll one of two nodes, and sample database connections | bash, Python 3, Docker |
| [`bridge-simulator/`](bridge-simulator/) | A script that plays the mainframe side of the bridge: drops READY files or POSTs, re-fires during and after the run, loses a DONE file once, and counts how many chains started | Python 3 |
| [`results/`](results/) | Every measured number, with the command, the machine and the date it came from, and a list of what did not run | |

## The interface contract both implementations follow

- `POST /chains/nightly-close?businessDate=YYYY-MM-DD&runNumber=N` starts the chain under a deterministic id derived from `nightly-close|businessDate|runNumber`. Same id and same input returns the existing run (200). Same id with a different input file is rejected with the existing run and a reason (409). A new run number is an explicit rerun.
- `GET /chains/nightly-close/{businessDate}?runNumber=N` returns the state, every step, the partitions done, and the lateness against the 06:00 SLA.
- `step_executions` records every attempt of every step; `deliveries` has a UNIQUE index on the idempotency key so a duplicate delivery is counted as a rejected insert, never as a second delivery.
- A `./markers/<step>.entered` file on entering each step, so the harness can `kill -9` the JVM at an exact point.
- `./bridge/inbox/NIGHTLY-CLOSE-<businessDate>-READY` starts the chain through the same contract; `./bridge/outbox/NIGHTLY-CLOSE-<businessDate>-DONE` is written on completion.

## Running it

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)    # macOS; any JDK 25 works
cd harness && docker compose up -d --wait           # Postgres 16 on localhost:5440
./run_chain.sh jobrunr-oss 2026-10-23 --scale 1      # or springbatch, or jobrunr-pro
./kill_test.sh jobrunr-oss extract 3
```

Each module's README has the details. The inventory tooling runs from the checkout with `python3 -m ctminv --help`.

## JobRunr Pro

The `nightly-close-jobrunr/pro` module uses features that are not in the open-source library: batches, job chaining, a mutex, a rate limiter, business-day cron expressions, the self-learning worker policy and the transaction plugin. To build and run it you need a JobRunr Pro trial: [request one here](https://www.jobrunr.io/en/try-jobrunr-pro/), then export the two Maven repository variables and the licence it gives you:

```bash
export JOBRUNR_REPO_USER=...
export JOBRUNR_REPO_PASSWORD=...
export JOBRUNR_PRO_LICENSE=...
```

No credential or licence is committed to this repository, and none should ever be. The open-source module and the Spring Batch module run without any of this.

## BMC's sample files

BMC's Automation API quickstart samples are under a licence that does not allow redistribution, so they are not copied here. `definitions/control-m/fetch-bmc-sample.sh` downloads the first-job-flow sample for comparison into a gitignored path.

## Licence

MIT, see [LICENSE](LICENSE). The Control-M and Autosys definitions are ours and describe a fictional chain; Control-M is a trademark of BMC Software, Autosys of Broadcom.
