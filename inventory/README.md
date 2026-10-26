# inventory: know your estate before you migrate anything

`ctminv` reads Control-M and Autosys definitions, puts every job into one of four migration buckets, and searches the run history for dependencies that exist only in timing ("B always starts right after A") and not in any definition. It is plain Python 3.10+ with no dependencies; the tests need `pytest`.

## Install and run

```bash
cd inventory
python3 -m ctminv --help                       # works straight from the checkout

# or install it, with the test runner
python3 -m venv .venv && .venv/bin/pip install -e '.[test]'
.venv/bin/python -m pytest                     # 24 tests
```

| Command | What it does |
|---|---|
| `python3 -m ctminv parse FILES... [--edges]` | jobs (or edges) found in `.json`, `.xml` and `.jil` files |
| `python3 -m ctminv classify FILES... [--domain folder\|application\|regex:PATTERN] [--ignore-host] [--out buckets.csv]` | the four buckets, printed as a distribution plus a CSV with one row per job and the reason |
| `python3 -m ctminv history ctm\|autorep FILES... --out history.csv` | builds the run-history CSV from `ctm` JSON or `autorep` reports |
| `python3 -m ctminv timegap --history history.csv --definitions FILES... [--min-tracking 0.3]` | hidden-dependency candidates, per pair and grouped |
| `python3 -m ctminv edges FILES... --out edges.csv` | the definition edges as CSV, the input `timegap.sql` needs |
| `python3 -m ctminv synth [--jobs 9000]` | synthetic estate + 90 nights of history with planted hidden dependencies |
| `python3 -m ctminv evaluate --candidates C.csv --planted P.csv --definitions D.json` | precision and recall of timegap against the planted truth |

Each module also runs on its own: `python3 -m ctminv.parse_ctm_json`, `.parse_ctm_xml`, `.parse_jil`, `.classify`, `.timegap`, `.synth`, `.evaluate_timegap`.

## Step 1: export the definitions

| Scheduler | Command | Output | Verified from a public source? |
|---|---|---|---|
| Control-M (Automation API) | `ctm deploy jobs::get -s "ctm=*&folder=*" > jobs.json` | Automation API JSON | yes: BMC's quickstart script `104-promote-folder-with-existing-promotion-rules/promote_folder.sh` and a public export script ([1], [2]) |
| Control-M (Automation API) | `ctm deploy calendars::get > calendars.json` | calendar definitions (not parsed by ctminv, but you need them to rebuild the business-day logic) | yes: [2] |
| Control-M/EM (classic) | `exportdeffolder` with an XML arguments file | `DEFTABLE` XML | the utility exists and is deprecated from 9.0.22 (BMC doc, 403 to scripts, see SOURCES.md); the exact arguments: check your release |
| Control-M/EM (classic) | `exportdefcal` | calendar XML | only seen in a third-party script [3]; check your release |
| Autosys | `autorep -J ALL -q > estate.jil` | JIL | yes: Broadcom TechDocs, `-q` "generates a query report, which contains the current JIL definition" |

`parse` and `classify` accept any mix of files, so a hybrid estate (Control-M for distributed, something else feeding it) can be analysed in one go.

## Step 2: classify

Every job lands in exactly one bucket. The rules are applied in this order:

1. **cross_system**, platform: the job has an edge (in or out) to a job on the other side of the mainframe/distributed line.
2. **cross_system**, outside: the job waits for an event or job that no analysed definition produces (another Control-M server, a manual `ctm run event::add`, an Autosys `job^OTHER_INSTANCE`, or an Autosys `value(GLOBAL)` condition someone sets with `sendevent`).
3. **cross_system**, domain or host: an edge goes to a job in another domain, or to a job on another host. The domain key is configurable: `--domain folder` (default, top-level folder or Autosys top-level box), `--domain application`, or `--domain regex:PATTERN` (first capture group of the pattern applied to the job name, e.g. `regex:^([A-Z]{3})_`). The host rule follows the brief literally; pass `--ignore-host` when one application spreads over several agents (see the numbers below: it moves half of the cross_system jobs back to in_domain).
4. **mainframe_only**: the job runs on z/OS (Control-M `Job:zOS:*` types, an XML folder with `PLATFORM="MVS"`/`z/OS`, Autosys `ZOS`/`ZOSM`/`ZOSDST`, or a host name matching `--mainframe-host`, default `zos|z/os|mvs|mainframe|lpar|sysplex`) and all its edges stay on the mainframe.
5. **single**: no edges in or out and not inside a SubFolder or box.
6. **in_domain**: everything else, i.e. edges that all stay inside the domain, on the same host and platform.

A job inside a Control-M `SubFolder` or an Autosys `BOX` inherits the edges of its containers. Containers themselves are not classified. Edges come from `eventsToWaitFor`/`eventsToAdd` (and `Event:Add` actions inside `If` blocks, which are conditional edges but edges nonetheless), `Flow` sequences, XML `INCOND`/`OUTCOND`/`DOCOND`, and JIL `condition:` status functions. Event deletions are recorded but create no edge.

What the buckets mean for the migration: `single` and `in_domain` can move as a unit; `cross_system` needs a bridge (see `../bridge-simulator`) or has to move together with its partner; `mainframe_only` stays where it is for now.

## Step 3: collect run history

`timegap` reads one CSV with a header row and one row per run:

| column | meaning |
|---|---|
| `job_name` | job name as in the definitions |
| `folder` | Control-M top-level folder, Autosys top-level box, or empty |
| `order_date` | business date the run belongs to, `YYYY-MM-DD` |
| `start_time`, `end_time` | ISO 8601, e.g. `2026-10-27T01:02:11` |
| `status` | `OK` or `NOTOK` |
| `host` | agent or machine, may be empty |

Per job and night, timegap uses the first start and the last end, so a failed run plus its rerun counts as one long run.

**Control-M.** `ctm run jobs:status::get -s "ctm=*&folder=*"` returns JSON with `name`, `folder`, `status`, `startTime`, `endTime`, `orderDate`, `host` for every job in the active environment. Field names verified against BMC's generated API model; the value formats (`"Oct 2, 2023, 11:42:59 AM"`, order date `YYMMDD`) against BMC's workbench README. The active environment only keeps recent order dates, so snapshot it every morning for 90 days, then convert all snapshots in one go (runs that appear in several snapshots are counted once):

```bash
ctm run jobs:status::get -s "ctm=*&folder=*" > status-$(date +%F).json     # every morning, e.g. from cron
python3 -m ctminv history ctm status-*.json --out history.csv
```

If you cannot wait 90 days: BMC's API also has `GET /run/job/{jobId}/statistics` (CLI `ctm run job:statistics::get <jobId>`, spelling inferred from the REST path) with per-run `startTime`, `endTime` and `orderDate` from the statistics database. ctminv does not convert that output yet. Many sites also have the history in the Control-M/EM database; a direct export into the CSV schema works just as well.

**Autosys.** Autosys has no order date. `autorep -J <job> -s -r -<n>` reports the run `n` runs back (verified: `-r` takes a negative relative run number and only works with `-s` or `-d`). Note that `-n` is not a run count: it means "truncated names". Loop over jobs and run numbers, then convert; a run that starts before `--day-starts-at` (default 12:00) is assigned to the previous business date:

```bash
for j in $(autorep -J ALL -q | awk '/insert_job:/ {print $2}'); do
  for n in $(seq 0 89); do autorep -J "$j" -s -r -$n; done
done > autorep-history.txt
python3 -m ctminv history autorep autorep-history.txt --definitions estate.jil --out history.csv
```

Running `autorep` 90 times per job is slow on a large instance; the run data also lives in the Autosys database (`ujo_job_runs` in the versions we have seen, not verified against a current schema), and a direct query into the same CSV schema is the faster path.

## Step 4: find hidden dependencies

```bash
python3 -m ctminv timegap --history history.csv --definitions jobs.json --min-tracking 0.3 --out candidates.csv
```

The rule, exactly as specified: report (A, B) when B starts 0 to 15 minutes after A ends on at least 90% of the nights both ran (and on at least 10 such nights), and the definitions do not link A to B. "Link" means any directed path through events, conditions, flows and containers (`--direct-only` checks only a direct edge). Window, confidence and minimum nights are flags.

The search is exact without comparing every pair: a pair can only reach 90% if it hits on one of its first floor(0.1 x shared nights) + 1 shared nights, so pairs are only recorded while that is still possible and are dropped as soon as their misses exceed the allowance.

Two refinements, both added after measuring the plain rule on the synthetic estate:

- `--min-tracking R` keeps a pair only if B's start time moves with A's end time over the hit nights: the 99.9% lower bound (Fisher z) of their correlation must be at least R. Two jobs on fixed clock times with stable runtimes can sit five minutes apart every night without depending on each other; this is the filter that removes them. The bound rather than the raw correlation, because with a million pairs tested, r = 0.85 over 13 nights happens by chance.
- Output is also grouped by **flow** (the connected part of the defined graph that A belongs to) in `<out>-groups.csv`. If B waits for one partition of a 500-way fan-out, all 500 partitions end at about the same time and all 500 pairs qualify; timing cannot tell which one B really needs, but it can tell which flow. Review the grouped file.

`timegap.sql` is the same rule (with the tracking bound as an optional line) as one PostgreSQL query over a `run_history` table and a `definition_edges` table (`python3 -m ctminv edges ... --out edges.csv`). It was checked with the PostgreSQL parser and run on DuckDB with results identical to the Python code on a 1,500-job estate (see SOURCES.md); it has not been run on a PostgreSQL server yet. It is slow at full size: on the 9,000-job synthetic estate DuckDB had not finished after 13 minutes, against about 40 seconds for the Python version.

## Measured on the synthetic estate

`python3 -m ctminv synth` (seed 42) builds 9,000 job definitions in 123 folders (108 distributed applications, 15 mainframe folders), 8,407 event edges, six fan-out chains of up to 500 partitions, 270 cross-folder edges, 150 planted hidden dependencies, and 90 nights of history (763,081 runs). Of the planted dependencies about 70% wait for A on 93 to 100% of the nights and the rest on 80 to 90%, below the 90% threshold on purpose. 114 of the 150 are visible to the rule (realised hit rate at least 90% on at least 10 shared nights).

Timings on an Apple M4 Pro, Python 3.14.5:

| Step | Time |
|---|---|
| `synth --jobs 9000` (build 0.01 s, simulate 0.7 s, write 1.7 to 1.9 s) | 2.3 to 2.7 s |
| `classify` on the 5.3 MB definitions file | 0.13 s |
| `timegap` (load 2.0 s, search 36 to 45 s), peak memory about 670 MB | 39 to 47 s |

Bucket distribution (domain = folder):

| bucket | default | `--ignore-host` |
|---|---|---|
| single | 1,981 (22.0%) | 1,981 (22.0%) |
| in_domain | 4,907 (54.5%) | 5,500 (61.1%) |
| cross_system | 1,128 (12.5%) | 535 (5.9%) |
| mainframe_only | 984 (10.9%) | 984 (10.9%) |

Twenty percent of the synthetic applications run on two agents; that is where the 593 host-only crossings come from.

Timegap against the planted truth (seed 42; seed 7 in brackets):

| | plain rule (as specified) | with `--min-tracking 0.3` |
|---|---|---|
| candidate pairs | 167,137 (101,253) | 9,439 (4,048) |
| pair precision | 0.1% (0.1%) | 1.2% (2.8%) |
| groups (flow of A, B) to review | 79,390 (70,192) | 211 (202) |
| group precision | 0.2% (0.2%) | **73.5% (71.8%)** |
| recall, visible planted (114 / 115) | 100% (100%) | **98.2% (97.4%)** |
| recall, all 150 planted | 76.0% (76.7%) | 74.7% (74.7%) |

What this says:

- The plain 15-minute / 90% rule finds every hidden dependency it can see, and buries them under coincidences: in a 12-hour batch window with 9,000 jobs, many pairs of fixed-time jobs sit a few minutes apart every night. On a real estate expect the same; do not hand the plain output to people.
- Asking B's start to follow A's end (tracking) and grouping by flow brings it down to about 200 groups of which roughly three in four point at a real dependency, at the cost of losing 2 to 3 of the visible ones.
- A quarter of the planted dependencies are never found, because on more than 10% of the nights B did not wait for A. Those are the flaky hand-offs (an operator starts B, a file arrives by another route) that also cause incidents; timing analysis will not show them, interviews with the operators will.
- The synthetic estate is our model of an estate, not yours: one start-time jitter (3 minutes), one runtime spread (25%), a 12-hour window. Run `evaluate` on your own planted test (for example, re-plant a few known dependencies by removing their events from a copy of the definitions) before you trust the numbers on real data.

Reproduce:

```bash
python3 -m ctminv synth --out-dir synth-out
python3 -m ctminv classify synth-out/definitions.json --out synth-out/buckets.csv
python3 -m ctminv timegap --history synth-out/history.csv --definitions synth-out/definitions.json \
    --min-tracking 0.3 --out synth-out/candidates.csv
python3 -m ctminv evaluate --candidates synth-out/candidates.csv --planted synth-out/planted.csv \
    --definitions synth-out/definitions.json
```

`synth-out/` is gitignored (the history is 64 MB).

## Layout

```
ctminv/
  model.py              Job, Edge, Estate; event and condition linking; format detection
  parse_ctm_json.py     Automation API JSON (Defaults, Folder/SubFolder, Flow, events, resources)
  parse_ctm_xml.py      exportdeffolder XML, tolerant (see SOURCES.md for why)
  parse_jil.py          JIL and autorep -q output
  classify.py           the four buckets
  history.py            run-history schema and converters
  timegap.py            hidden-dependency search
  synth.py              synthetic estate and history
  evaluate_timegap.py   precision and recall against the planted truth
  cli.py, __main__.py   python3 -m ctminv ...
timegap.sql             the timegap rule as one PostgreSQL query
tests/                  pytest, small fixtures written for the tests
SOURCES.md              where every format detail comes from
```

[1]: https://github.com/controlm/automation-api-quickstart/blob/03e17b08b8fd226160c4850d21dce315a8e74104/control-m/104-promote-folder-with-existing-promotion-rules/promote_folder.sh
[2]: https://github.com/dcompane/controlm_toolset/blob/b3cd325e434d1710a78985de9277ef1443856b70/Helix_ControlM_export/ctm_export_all.sh
[3]: https://github.com/dcompane/controlm_toolset/blob/b3cd325e434d1710a78985de9277ef1443856b70/misc_tools/emminer/emminer.pl
