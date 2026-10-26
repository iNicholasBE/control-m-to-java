# BMC sample: `AutomationAPISampleFlow.json`

**Origin:** BMC Software, repository `controlm/automation-api-quickstart`, path `control-m/101-create-first-job-flow/AutomationAPISampleFlow.json`.
https://github.com/controlm/automation-api-quickstart/blob/master/control-m/101-create-first-job-flow/AutomationAPISampleFlow.json

**Licence:** the repository's `LICENSE` is the "Control-M Developers Kit License Agreement" (Rev. 03-16-2017), not an open source licence. GitHub reports it as `NOASSERTION`. Section 5 says the licensee "may not disclose the BMC Kit or any part thereof to any third party", and Section 2 limits use to defining and running workflows with a licensed Control-M. Copying the file into a public repository would disclose it to third parties, so **this repository does not contain a copy**.

**To get it locally**, run:

```bash
./fetch-bmc-sample.sh
```

This downloads the file from BMC's repository to `bmc-sample-AutomationAPISampleFlow.json` next to this README. That file name is in `.gitignore` here, so it is not committed by accident. By downloading it you accept BMC's licence terms yourself.

**Why it is useful:** it is the smallest official example of the format. It shows a `Defaults` block, a `Folder`, `Job:Command`, `Job:Script`, a `When` block with `WeekDays`, `FromTime` and `ToTime`, an `ActionIfFailure` mail, and the `Flow` object with a `Sequence`. The `inventory/` parser handles every one of these, including `Flow`.
