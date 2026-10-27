#!/usr/bin/env bash
# The Pro hand-offs without the chain: a batch job with N no-op children plus a runAfterSuccessOf continuation
# (HandOffProbe in nightly-close-jobrunr/pro). Starts the Pro app on its own database, runs each probe, prints the
# probe's timeline as JSON, appends one row per probe to results/jobrunr/handoff-probe.csv.
#
#   ./handoff_probe.sh <children> <rateLimited true|false> [<children> <rateLimited>]... [-- extra app args]
#   ./handoff_probe.sh 20 true 20 false 10000 true 10000 false
#
# Needs JOBRUNR_PRO_LICENSE. Uses database nightly_jobrunr_probe and port 8282 unless JOBRUNR_DB / IMPL_PORT say otherwise.
source "$(dirname "$0")/lib.sh"

export JOBRUNR_DB="${JOBRUNR_DB:-nightly_jobrunr_probe}" IMPL_PORT="${IMPL_PORT:-8282}"
load_impl jobrunr-pro
[[ -n "${JOBRUNR_PRO_LICENSE:-}" ]] || die "export JOBRUNR_PRO_LICENSE first"
PROBES=(); EXTRA_ARGS=()
while [[ $# -gt 0 ]]; do
  if [[ "$1" == "--" ]]; then shift; EXTRA_ARGS=("$@"); break; fi
  [[ $# -ge 2 ]] || die "usage: handoff_probe.sh <children> <rateLimited> [...] [-- extra app args]"
  PROBES+=("$1:$2"); shift 2
done
[[ ${#PROBES[@]} -gt 0 ]] || PROBES=("20:true")

postgres_up
build_impl
reset_db
WORK="$HARNESS/work/handoff-probe"
prepare_workdir "$WORK"
PID=$(start_app "$WORK" "$IMPL_PORT" "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}")
trap 'stop_app "$PID"' EXIT
wait_ready "$IMPL_PORT" "$PID"
sleep 6  # let the background job server finish its first poll

CSV="$RESULTS_DIR/handoff-probe.csv"
[[ -f "$CSV" ]] || echo "run_at_utc,children,rate_limited,poll_interval_s,batch_method_s,handoff1_batch_return_to_first_child_s,first_to_last_child_start_s,last_child_end_to_batch_succeeded_s,batch_succeeded_to_continuation_enqueued_s,continuation_enqueued_to_started_s,handoff2_last_child_end_to_continuation_s,total_s,extra_args" >"$CSV"
POLL=$(printf '%s\n' "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}" | sed -n 's/^--jobrunr.background-job-server.poll-interval-in-seconds=//p'); POLL="${POLL:-5}"

for probe in "${PROBES[@]}"; do
  children="${probe%%:*}"; limited="${probe##*:}"
  RUN_AT=$(iso_now)
  id=$(curl -s -X POST "http://localhost:$IMPL_PORT/probe/batch?children=$children&rateLimited=$limited" | python3 -c 'import json,sys; print(json.load(sys.stdin)["probeId"])')
  log "probe $id: $children children, rate limited $limited"
  deadline=$(( $(date +%s) + 3600 ))
  until [[ "$(curl -s "http://localhost:$IMPL_PORT/probe/batch/$id" | python3 -c 'import json,sys; print(json.load(sys.stdin)["done"])')" == True ]]; do
    (( $(date +%s) < deadline )) || die "probe $id not done within an hour"
    sleep 1
  done
  sleep 1
  REPORT=$(curl -s "http://localhost:$IMPL_PORT/probe/batch/$id")
  printf '%s\n' "$REPORT" | python3 -m json.tool
  python3 - "$REPORT" "$RUN_AT" "$POLL" "${EXTRA_ARGS[*]+"${EXTRA_ARGS[*]}"}" "$limited" >>"$CSV" <<'EOF'
import json, sys
r, run_at, poll, extra, limited = json.loads(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
s = r["seconds"]
keys = ["batchMethod_createChildren", "handOff1_batchReturnToFirstChildStart", "firstToLastChildStart",
        "lastChildEndToBatchSucceeded", "batchSucceededToContinuationEnqueued", "continuationEnqueuedToStarted",
        "handOff2_lastChildEndToContinuationStart", "total_submitToContinuationStart"]
print(",".join([run_at, str(r["children"]), limited, poll] + [f"{s[k]:.2f}" if s[k] is not None else "" for k in keys] + [extra]))
EOF
done
