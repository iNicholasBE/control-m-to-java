# Sources for `nightly-close.json`

Checked on 2026-10-26. BMC's own documentation pages on `documents.bmc.com` and `docs.bmc.com` returned HTTP 403 to scripted requests on that date, so every field name below was confirmed against BMC-published code on GitHub instead: the official Python client (`controlm/ctm-python-client`, BSD-3-Clause), whose `aapi` model maps every Python attribute to the exact Automation API JSON name (`'_aapi_repr_': 'FieldName'`), and BMC's sample definitions. The documentation URLs are listed too, so you can read them in a browser.

## Primary sources

| ID | What | URL |
|----|------|-----|
| S1 | BMC quickstart, first job flow (`Folder`, `Job:Command`, `Flow`/`Sequence`, `When` with `WeekDays`/`FromTime`/`ToTime`) | https://raw.githubusercontent.com/controlm/automation-api-quickstart/master/control-m/101-create-first-job-flow/AutomationAPISampleFlow.json |
| S2 | Real `ctm deploy jobs::get`-style export (`eventsToWaitFor`/`WaitForEvents`, `eventsToAdd`/`AddEvents`, `eventsToDelete`/`DeleteEvents`, `Resource:Lock` with `LockType`, `Calendar:RuleBased` inside `When.RuleBasedCalendars`, `Job:SLAManagement` with `CompleteIn`) | https://raw.githubusercontent.com/Orch3strator/ctm-privacy-guard/main/src/ctm/onboarding.json |
| S3 | BMC Python client, job model (`Job:Command`, `Job:FileWatcher`, `Job:FileWatcher:Create` with `Path`/`SearchInterval`/`TimeLimit`/`MinimumSize`, `Job:SLAManagement` with `CompleteBy{Time,Days}`/`CompleteIn`/`ServiceName`/`ServicePriority`/`JobRunsDeviationsTolerance`, `RerunLimit{Times,Every,Units,From}`, job `When{FromTime,ToTime,WeekDays,RuleBasedCalendars{Included,Excluded},ConfirmationCalendars}`) | https://github.com/controlm/ctm-python-client/blob/df6b42ee4ab323383a41900967370c7098fdfe55/src/aapi/job.py |
| S4 | BMC Python client, resources (`Resource:Pool` with `Quantity`/`IfFail`/`IfOk`; `Resource:Lock` with `LockType` Shared/Exclusive) | https://github.com/controlm/ctm-python-client/blob/df6b42ee4ab323383a41900967370c7098fdfe55/src/aapi/resource.py |
| S5 | BMC Python client, calendars (`Calendar:RuleBased` `When` fields, `ConfirmationCalendars{Calendar,ShiftBy,ExceptionPolicy}` with `DoNotOrder`) | https://github.com/controlm/ctm-python-client/blob/df6b42ee4ab323383a41900967370c7098fdfe55/src/aapi/calendar.py |
| S6 | BMC Python client, folder model (`Folder`, `ControlmServer`, `OrderMethod`, folder `When` with `RuleBasedCalendars`) | https://github.com/controlm/ctm-python-client/blob/df6b42ee4ab323383a41900967370c7098fdfe55/src/aapi/basefolder.py |
| S7 | BMC VS Code extension snippets (`Job:FileWatcher:Create` example; `Resource:Pool`/`Resource:Lock` example; `Job:SLAManagement` example) | https://github.com/controlm/ctm-vscode-extension/blob/6ee3eb9f7e45c6990d58069371dfdaae83029825/snippets/ctm.json.job.types.code-snippets |
| S8 | BMC VS Code extension demo (`Job:SLAManagement` with `"CompleteBy": {"Time": "12:00", "Days": "0"}`) | https://github.com/controlm/ctm-vscode-extension/blob/6ee3eb9f7e45c6990d58069371dfdaae83029825/snippets/ctm.json.demo.ehr.code-snippets |
| S9 | BMC community "kitchen sink" (`Calendar:RuleBased` with `ConfirmationCalendars`/`ExceptionPolicy`, `Action:Rerun` inside an `If` block, `Rerun`) | https://github.com/controlm/automation-api-community-solutions/blob/f35f52500683ac7551342deac6de711dbb2a55ab/8-jobs-as-code/kitchensink-with-jinja2/kitchensink.json |
| S10 | BMC community file watcher sample (`Job:FileWatcher:Create`, `Path`, `SearchInterval`, `TimeLimit`, `MinimumSize`) | https://github.com/controlm/automation-api-community-solutions/blob/f35f52500683ac7551342deac6de711dbb2a55ab/8-jobs-as-code/file-watcher-example/file-watcher-job.json |

## Documentation pages (403 to scripts, readable in a browser)

| What | URL |
|------|-----|
| File Watcher job type | https://documents.bmc.com/supportu/API/Helix/en-US/Documentation/API_CodeRef_JobTypes_FileWatcher.htm |
| SLA Management job type | https://documents.bmc.com/supportu/API/Monthly/en-US/Documentation/API_CodeRef_JobTypes_sla.htm |
| Resource Pools (concept) | https://documents.bmc.com/supportu/9.0.22/en-US/Documentation/Resource_Pools.htm |
| Run service (`ctm run ...`) | https://documents.bmc.com/supportu/API/Monthly/en-US/Documentation/API_Services_RunService.htm |

## Field by field

| Field in `nightly-close.json` | Confirmed in | Status |
|---|---|---|
| `"Type": "Folder"`, `ControlmServer`, `OrderMethod`, `Application`, `SubApplication`, `Description` | S1, S2, S6 | verified |
| Folder `When.RuleBasedCalendars.Included` + inline `"Type": "Calendar:RuleBased"` | S2, S5, S6, S9 | verified |
| `Calendar:RuleBased` `When.WeekDays`, `MonthDays`, `DaysRelation` | S2, S5 | verified |
| `ConfirmationCalendars{Calendar, ExceptionPolicy: "DoNotOrder"}` for holidays | S5, S9 | field names verified; the holiday calendar `BANK_WORKDAYS` itself is a `Calendar:Regular` you deploy separately (export yours with `ctm deploy calendars::get`) |
| Job `When.FromTime` (`"0100"`, HHMM) | S1, S3 | verified |
| Job `When.RuleBasedCalendars.Included: ["USE PARENT"]` | S2, S9 | verified |
| `"Type": "Job:FileWatcher:Create"`, `Path`, `SearchInterval` (seconds), `TimeLimit` (minutes), `MinimumSize` | S3, S7, S10 | verified. Note: `Job:FileWatcher:Create` and `:Delete` are the Automation API wrappers around the agent's `ctmfw` file watcher. On releases or agents without this job type, run `ctmfw` from a `Job:Command` instead. We used the native job type. |
| `"Type": "Job:Command"`, `Command`, `Host`, `RunAs` | S1, S3 | verified |
| `eventsToWaitFor` / `"Type": "WaitForEvents"` / `Events: [{"Event": ...}]` | S2 | verified |
| `eventsToAdd` / `"Type": "AddEvents"` | S2 | verified |
| `eventsToDelete` / `"Type": "DeleteEvents"` | S2 | verified |
| `"Type": "Resource:Pool"`, `Quantity` | S4, S7 | verified. The pool's total of 20 is not part of the job definition: it is created on the server (REST `POST /run/resource/{server}`, CLI `ctm run resource::add`; verify the CLI syntax against your release). |
| `"Type": "Resource:Lock"`, `LockType: "Exclusive"` | S2, S4, S7 | verified |
| `RerunLimit{Times, Every, Units}` | S3 (model), S7 (z/OS example) | field names verified in the client model; the exact rerun semantics (interplay with `Action:Rerun`) could not be read from a BMC doc page: verify against your release |
| `ActionIfFailure` `{"Type": "If", "CompletionStatus": "NOTOK", ...: {"Type": "Action:Rerun"}}` | S1 (If/NOTOK), S9 (Action:Rerun) | verified |
| `"Type": "Job:SLAManagement"`, `ServiceName`, `ServicePriority`, `JobRunsDeviationsTolerance` | S2, S3, S7, S8 | verified |
| `CompleteBy{Time: "06:00", Days: "0"}` | S3, S8 | verified field names. `Days` counts days after the order date; `"0"` assumes your New Day time is before 01:00. Verify against your site's New Day setting. |
| `%%$ODATE` in paths and commands | not confirmed from a fetched page | Control-M system variable for the order date as YYYYMMDD (`%%ODATE` is YYMMDD). Verify against your release. |

## The BMC first-job-flow sample

See `bmc-sample-README.md`: its licence does not allow redistribution, so this repository ships a fetch script instead of a copy.
