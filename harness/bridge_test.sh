#!/usr/bin/env bash
# Plays the mainframe side of the Control-M bridge against an implementation with bridge-simulator/mainframe_side.py:
# re-fire during the run, lose the first DONE and re-drive, re-fire after DONE. Checks one run per business date.
#
#   ./bridge_test.sh <impl> <file|rest> [extra simulator flags...]
#
# Appends one row to <results>/bridge.csv.
source "$(dirname "$0")/lib.sh"

[[ $# -ge 2 ]] || die "usage: bridge_test.sh <impl> <file|rest> [simulator flags]"
load_impl "$1"; TRANSPORT="$2"; shift 2
FLAGS=("$@")
[[ ${#FLAGS[@]} -gt 0 ]] || FLAGS=(--refire-during-run --refire-after-done --drop-done-once)
DATE="${BUSINESS_DATE:-2026-10-23}"

postgres_up
build_impl
POSITIONS=$(positions_file "$DATE" 1)
WORK="$HARNESS/work/$IMPL-bridge"
reset_db
prepare_workdir "$WORK"
PID=$(start_app "$WORK" "$IMPL_PORT")
trap 'stop_app "$PID"' EXIT
wait_ready "$IMPL_PORT" "$PID"

RUN_AT=$(iso_now)
drop_positions "$WORK" "$POSITIONS"   # the file arrives; the simulator then plays the mainframe's bridge signals
REPORT="$WORK/bridge-report.json"
TRANSPORT_ARGS=(--status-url "http://localhost:$IMPL_PORT")
[[ "$TRANSPORT" == rest ]] && TRANSPORT_ARGS=(--rest "http://localhost:$IMPL_PORT")
set +e
python3 "$REPO/bridge-simulator/mainframe_side.py" --inbox "$WORK/bridge/inbox" --outbox "$WORK/bridge/outbox" \
  --business-date "$DATE" "${TRANSPORT_ARGS[@]}" "${FLAGS[@]}" --json-report "$REPORT" >"$WORK/bridge-simulator.log" 2>&1
EXIT=$?
set -e

SUMMARY=$(python3 - "$REPORT" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
events = r["events"]
dropped = any("dropped" in e for e in events)
after_drop = [e for e in events[next((i for i, e in enumerate(events) if "dropped" in e), len(events)):]]
if not dropped:
    how = "n/a"
elif any("DONE arrived" in e for e in after_drop):
    how = "DONE file re-emitted"
elif any("learned from the status endpoint" in e for e in after_drop):
    how = "status endpoint only"
else:
    how = "never"
print(f'{r["exit_code"]},{len(r["triggers"])},{len(r["run_ids"])},{how},{r["elapsed_seconds"]}')
PY
)
RUNS=$(sql "SELECT count(*) FROM chain_runs WHERE business_date = '$DATE'")
ACCEPTED=$(sql "SELECT count(*) FROM deliveries WHERE business_date = '$DATE'")
REJECTED=$(sql "SELECT count(*) FROM delivery_rejections WHERE business_date = '$DATE'")
STEP_RERUNS=$(sql "SELECT count(*) FROM (SELECT step FROM step_executions GROUP BY run_id, step HAVING count(*) > 1) s")

CSV="$RESULTS_DIR/bridge.csv"
[[ -f "$CSV" ]] || echo "impl,transport,flags,run_at_utc,simulator_exit,triggers_sent,run_ids_seen,completion_after_lost_done,elapsed_s,chain_runs_for_date,deliveries_accepted,duplicate_deliveries_rejected,steps_run_more_than_once" >"$CSV"
echo "$IMPL,$TRANSPORT,${FLAGS[*]},$RUN_AT,$SUMMARY,$RUNS,$ACCEPTED,$REJECTED,$STEP_RERUNS" >>"$CSV"
log "bridge $IMPL $TRANSPORT: $(tail -1 "$CSV")"
cp "$WORK/bridge-simulator.log" "$HARNESS/work/logs-$IMPL-bridge-$TRANSPORT.log"
