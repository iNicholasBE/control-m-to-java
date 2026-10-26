#!/usr/bin/env bash
# The deploy-time duplicate test: two instances on one database, and a rolling restart while the chain runs.
#
#   ./rolling_restart.sh <impl> [--scale 1|10]
#
# Instance A and B share the database and the work directory (think: two pods on one volume). Once the extract
# has started, A is stopped gracefully (SIGTERM) and started again, then B. Appends to <results>/rolling-restart.csv.
source "$(dirname "$0")/lib.sh"

[[ $# -ge 1 ]] || die "usage: rolling_restart.sh <impl> [--scale 1|10]"
load_impl "$1"; shift
SCALE=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --scale) SCALE="$2"; shift 2 ;;
    *) die "unknown option $1" ;;
  esac
done
DATE="${BUSINESS_DATE:-2026-10-23}"
PORT_A=$((IMPL_PORT + 10)); PORT_B=$((IMPL_PORT + 20))

postgres_up
build_impl
POSITIONS=$(positions_file "$DATE" "$SCALE")
REFERENCE="$HARNESS/work/reference/$IMPL/${SCALE}x/REPORT-$DATE-1.csv"
WORK="$HARNESS/work/$IMPL-rolling"
reset_db
prepare_workdir "$WORK"

PID_A=$(start_app "$WORK" "$PORT_A"); wait_ready "$PORT_A" "$PID_A"
PID_B=$(start_app "$WORK" "$PORT_B"); wait_ready "$PORT_B" "$PID_B"
trap 'stop_app "$PID_A"; stop_app "$PID_B"' EXIT

RUN_AT=$(iso_now); T0=$(now_ms)
trigger "$WORK" "$PORT_A" "$DATE" file
wait_for_file "$WORK/markers/extract.entered" 120 || die "extract never started"

restart() {  # restart <name> <port> <pid var>
  local t; t=$(now_ms)
  log "graceful stop of instance $1 (port $2)"
  stop_app "${!3}"
  printf -v "$3" '%s' "$(start_app "$WORK" "$2")"
  wait_ready "$2" "${!3}"
  log "instance $1 back after $(secs_between "$t" "$(now_ms)")s"
}
restart A "$PORT_A" PID_A
restart B "$PORT_B" PID_B

if wait_for_file "$WORK/bridge/outbox/NIGHTLY-CLOSE-$DATE-DONE" 1800; then
  TOTAL=$(secs_between "$T0" "$(now_ms)"); NOTES=""
else
  TOTAL=""; NOTES="no DONE file within 1800s"
fi

RUN_ID=$(run_id_of "$DATE")
MULTI=$(sql "SELECT coalesce(string_agg(step || ':' || n, ' ' ORDER BY step), '') FROM
               (SELECT step, count(*) n FROM step_executions WHERE run_id = '$RUN_ID' GROUP BY step HAVING count(*) > 1) s")
RERUN=$(sql "SELECT coalesce(string_agg(DISTINCT a.step, ' '), '') FROM step_executions a
              WHERE a.run_id = '$RUN_ID' AND a.outcome = 'SUCCEEDED'
                AND EXISTS (SELECT 1 FROM step_executions b WHERE b.run_id = a.run_id AND b.step = a.step AND b.attempt > a.attempt)")
ACCEPTED=$(sql "SELECT count(*) FROM deliveries WHERE business_date = '$DATE'")
REJECTED=$(sql "SELECT count(*) FROM delivery_rejections WHERE business_date = '$DATE'")
IDENTICAL=n/a
if [[ -f "$REFERENCE" ]]; then
  if cmp -s "$WORK/outbox/REPORT-$DATE-1.csv" "$REFERENCE"; then IDENTICAL=yes; else IDENTICAL=no; fi
fi

CSV="$RESULTS_DIR/rolling-restart.csv"
[[ -f "$CSV" ]] || echo "impl,scale,run_at_utc,total_s,steps_with_more_than_one_attempt,completed_steps_rerun,deliveries_accepted,duplicate_deliveries_rejected,report_identical,notes" >"$CSV"
echo "$IMPL,$SCALE,$RUN_AT,$TOTAL,$MULTI,$RERUN,$ACCEPTED,$REJECTED,$IDENTICAL,$NOTES" >>"$CSV"
log "rolling restart: more than one attempt [$MULTI], completed steps rerun [$RERUN], deliveries $ACCEPTED accepted / $REJECTED rejected"
cp "$WORK/app-$PORT_A.log" "$HARNESS/work/logs-$IMPL-rolling-A.log"; cp "$WORK/app-$PORT_B.log" "$HARNESS/work/logs-$IMPL-rolling-B.log"
