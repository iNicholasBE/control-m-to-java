#!/bin/sh
# Downloads BMC's first-job-flow sample for local reference.
# It is not committed: see bmc-sample-README.md for the licence reason.
set -eu
cd "$(dirname "$0")"
curl -fsSL -o bmc-sample-AutomationAPISampleFlow.json \
  https://raw.githubusercontent.com/controlm/automation-api-quickstart/master/control-m/101-create-first-job-flow/AutomationAPISampleFlow.json
echo "Saved bmc-sample-AutomationAPISampleFlow.json (BMC Control-M Developers Kit License, do not redistribute)"
