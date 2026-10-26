# Sources for the inventory tools

Checked on 2026-10-26.

## Control-M Automation API JSON (`parse_ctm_json.py`)

Field names as in `definitions/control-m/SOURCES.md`, mainly BMC's Python client model
(https://github.com/controlm/ctm-python-client, `src/aapi/*.py`, BSD-3-Clause) and the samples in
https://github.com/controlm/automation-api-quickstart and
https://github.com/Orch3strator/ctm-privacy-guard/blob/main/src/ctm/onboarding.json (a real export with
`eventsToWaitFor`, `eventsToAdd`, `eventsToDelete`, `SubFolder`-level events and `Event:Add` inside `If` blocks).
`Job:zOS:*` as the mainframe job type family: `Job:zOS:Member` and `Job:zOS:InStreamJCL` are named in BMC's
Automation API release notes (https://documents.bmc.com/supportu/API/9.0.21/9.0.21.300/en-US/Documentation/API_What_s_New.htm).

## Control-M XML (`parse_ctm_xml.py`)

BMC ships the schema (`Folder.xsd`, older `deftable.dtd`) with Control-M/EM; we could not download it. The
documentation pages for `exportdeffolder` returned HTTP 403 to scripts:
https://documents.bmc.com/supportu/9.0.21/en-US/Documentation/Utilities/exportdeffolder.htm
(the search snippet says the utility is deprecated, "no longer enhanced and support will be discontinued in
version 9.0.22"). The parser is therefore **tolerant**: it was written from these public example files and
ignores anything it does not know.

| What it confirms | URL |
|---|---|
| `DEFTABLE` > `FOLDER` (`FOLDER_NAME`, `PLATFORM="UNIX"`, `DATACENTER`) > `JOB` (`JOBNAME`, `APPLICATION`, `SUB_APPLICATION`, `NODEID`, `TASKTYPE`, `CMDLINE`), `INCOND` (`AND_OR`), `OUTCOND` with `SIGN="+"`/`"-"`, `QUANTITATIVE` (`QUANT`, `ONOK`, `ONFAIL`), `ON`/`DOMAIL` | https://github.com/benkaan001/controlm-xml-automation/blob/7849381ed87cee404d144a626aadc2a529eedb20/sample_data/sample_controlm_dev.xml (MIT) |
| mainframe export: `FOLDER PLATFORM="MVS"`, `JOB MEMNAME=... MEMLIB=...`, `CONTROL NAME=... TYPE="E"/"S"`, `OUTCOND SIGN="ADD"`, `DOCOND ... SIGN="DEL"` inside `ON` | https://github.com/NEXRY-LLC/TidalMigrationTools/blob/0911e8629e8982736ee6051c262b3519ddac252a/cfg/cvs-bmc-mainframe/MTSMXL/WaveOneB.xml |
| older format: `<!DOCTYPE DEFTABLE SYSTEM "deftable.dtd">`, `TABLE TABLE_NAME=...`, scheduling attributes on `JOB` (`DAYS`, `WEEKDAYS`, `TIMEFROM`, `TIMETO`, `CYCLIC`, `INTERVAL`) | https://github.com/rundeck/job-converter-control-m/blob/f55ec3463c248f44f4cd08fe527b7b7c411761ef/example/example_jobs.xml |

`SMART_FOLDER`, `SUB_FOLDER`, `SMART_TABLE` and `SCHED_TABLE` are accepted as containers because they appear
in exports from different releases; we did not find a public example of every one, so treat their handling
as best effort and check the parser output (`python -m ctminv parse your-export.xml`) against your estate.

## Autosys JIL (`parse_jil.py`)

Broadcom TechDocs, AutoSys Workload Automation 12.0.01, see `definitions/autosys/SOURCES.md`. In particular the
`condition` attribute page (status functions and their one-letter forms, `exitcode()`, `value()`, look-back
`s(job,hh.mm)`, cross-instance `job^INS`, `&` and `|`):
https://techdocs.broadcom.com/us/en/ca-enterprise-software/intelligent-automation/autosys-workload-automation/12-0-01/reference/ae-job-information-language/jil-job-definitions/condition-attribute-define-starting-conditions-for-a-job.html
and the `job_type` page for the z/OS types (`ZOS`, `ZOSM`, `ZOSDST`):
https://techdocs.broadcom.com/us/en/ca-enterprise-software/intelligent-automation/autosys-workload-automation/12-0-01/reference/ae-job-information-language/jil-job-definitions/job-type-attribute-specify-job-type.html

## Run history (`history.py`)

| Command | What we could verify | URL |
|---|---|---|
| `ctm run jobs:status::get` | output fields `jobId`, `name`, `folder`, `type`, `status`, `startTime`, `endTime`, `orderDate`, `host`, `numberOfRuns` (BMC's generated `JobRunStatus` model, OpenAPI 9.22.30) | https://github.com/controlm/ctm-python-client/blob/df6b42ee4ab323383a41900967370c7098fdfe55/src/clients/ctm_api_client/models/job_run_status.py |
| same | example values: `"startTime": "Oct 2, 2023, 11:42:59 AM"`, `"orderDate": "231002"`, status strings such as `"Executing"`, `"Wait Condition"` | https://github.com/controlm/workbench/blob/d82c064be507590b54823f20eea08ffb81227078/README.md |
| `ctm run job:statistics::get` (REST `GET /run/job/{jobId}/statistics`) | per-run `startTime`, `endTime`, `runTime`, `orderDate` (`StatisticsSingleRun` model); the CLI spelling is inferred from the REST path | https://github.com/controlm/ctm-python-client/blob/df6b42ee4ab323383a41900967370c7098fdfe55/src/clients/ctm_api_client/models/statistics_single_run.py |
| `autorep -J <job> -s -r -<n>` | `-r run_num` with a minus sign = n runs back, only with `-s` or `-d`; summary columns `Job Name`, `Last Start`, `Last End`, `ST/Ex`, `Run/Ntry`, `Pri/Xit` in `MM/DD/YYYY HH:MM:SS`; `-n` is "truncated names", not a run count | https://techdocs.broadcom.com/us/en/ca-enterprise-software/intelligent-automation/autosys-workload-automation/12-0-01/reference/ae-commands/monitor-and-report-on-workload/autorep-command-report-job-machine-and-variable-information.html |

## timegap.sql

Parsed with the PostgreSQL parser through `pglast` 8.7 (libpg_query) and run on DuckDB 2.0 against the Python
implementation on a 1,500-job, 60-night synthetic estate: 4,413 pairs from both, identical counts, medians and
tracking bounds. It has **not** been run on a PostgreSQL server yet.
