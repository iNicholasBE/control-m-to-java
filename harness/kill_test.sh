#!/usr/bin/env bash
# kill -9 the JVM at a precise point in the chain, restart it, and check what ran twice.
#
#   ./kill_test.sh <impl> <killPoint> <repeats>
#
# Kill points are marker names: watcher, extract, scoring-partition-3, scoring-partition-17, join, aggregate, report,
# deliver-before-call, deliver-after-call-before-record, recurring-trigger.
# The app pauses for a few seconds right after writing <killPoint>.entered (nightly-close.test.pause-at), so the
# kill lands there. The restart runs without the pause. Appends one row per repeat to <results>/kill-tests.csv.
source "$(dirname "$0")/lib.sh"

[[ $# -eq 3 ]] || die "usage: kill_test.sh <impl> <killPoint> <repeats>"
load_impl "$1"; KILL_POINT="$2"; REPEATS="$3"
DATE="${BUSINESS_DATE:-2026-10-23}"
TRANSPORT="${TRANSPORT:-file}"
PAUSE="${PAUSE:-PT3S}"

if [[ "$KILL_POINT" == "recurring-trigger" ]]; then
  # The trigger derives the business date from its own fire time (UTC), so the run is for today.
  DATE=$(date -u +%F)
  export TRIGGER_CRON="${TRIGGER_CRON_FOR_TEST:-*/10 * * * * *}"
  export TRIGGER_ENABLED=true
fi

postgres_up
build_impl
POSITIONS=$(positions_file "$DATE" 1)

# The reference: one clean run of the same implementation for the same date.
REFERENCE="$HARNESS/work/reference/$IMPL/REPORT-$DATE-1.csv"
if [[ ! -f "$REFERENCE" ]]; then
  log "no reference report for $IMPL $DATE yet, running a clean chain first"
  TRIGGER_CRON=- TRIGGER_ENABLED=false NO_BUILD=1 "$HARNESS/run_chain.sh" "$IMPL" "$DATE" --label "reference for kill tests" --save-reference
fi

CSV="$RESULTS_DIR/kill-tests.csv"
[[ -f "$CSV" ]] || echo "impl,kill_point,repeat,run_at_utc,killed_at_marker,resume_s,total_s,steps_with_more_than_one_attempt,completed_steps_rerun,interrupted_steps,chain_runs_for_date,deliveries_accepted,duplicate_deliveries_rejected,report_identical,notes" >"$CSV"

WORK="$HARNESS/work/$IMPL-kill"
for repeat in $(seq 1 "$REPEATS"); do
  log "$IMPL kill at '$KILL_POINT', repeat $repeat/$REPEATS"
  reset_db
  prepare_workdir "$WORK" "$POSITIONS"
  RUN_AT=$(iso_now)
  PID=$(start_app "$WORK" "$IMPL_PORT" "--nightly-close.test.pause-at=$KILL_POINT" "--nightly-close.test.pause-duration=$PAUSE")
  wait_ready "$IMPL_PORT" "$PID"

  T0=$(now_ms)
  [[ "$KILL_POINT" == "recurring-trigger" ]] || trigger "$WORK" "$IMPL_PORT" "$DATE" "$TRANSPORT"

  NOTES=""
  if wait_for_file "$WORK/markers/$KILL_POINT.entered" 600; then
    kill -9 "$PID"; wait "$PID" 2>/dev/null || true
    KILLED=yes
  else
    KILLED=no; NOTES="marker never appeared"
    stop_app "$PID"
  fi

  T_RESTART=$(now_ms)
  PID=$(start_app "$WORK" "$IMPL_PORT")
  if wait_for_file "$WORK/bridge/outbox/NIGHTLY-CLOSE-$DATE-DONE" 900; then
    T_DONE=$(now_ms)
    RESUME=$(secs_between "$T_RESTART" "$T_DONE"); TOTAL=$(secs_between "$T0" "$T_DONE")
  else
    RESUME=""; TOTAL=""; NOTES="${NOTES:+$NOTES; }no DONE file within 900s after restart"
  fi
  stop_app "$PID"

  RUN_ID=$(run_id_of "$DATE")
  MULTI=$(sql "SELECT coalesce(string_agg(step || ':' || n, ' ' ORDER BY step), '') FROM
                 (SELECT step, count(*) n FROM step_executions WHERE run_id = '$RUN_ID' GROUP BY step HAVING count(*) > 1) s")
  RERUN=$(sql "SELECT coalesce(string_agg(DISTINCT a.step, ' '), '') FROM step_executions a
                WHERE a.run_id = '$RUN_ID' AND a.outcome = 'SUCCEEDED'
                  AND EXISTS (SELECT 1 FROM step_executions b WHERE b.run_id = a.run_id AND b.step = a.step AND b.attempt > a.attempt)")
  INTERRUPTED=$(sql "SELECT coalesce(string_agg(step, ' ' ORDER BY step), '') FROM step_executions WHERE run_id = '$RUN_ID' AND outcome IS NULL")
  RUNS=$(sql "SELECT count(*) FROM chain_runs WHERE business_date = '$DATE'")
  ACCEPTED=$(sql "SELECT count(*) FROM deliveries WHERE business_date = '$DATE'")
  REJECTED=$(sql "SELECT count(*) FROM delivery_rejections WHERE business_date = '$DATE'")
  if cmp -s "$WORK/outbox/REPORT-$DATE-1.csv" "$REFERENCE"; then IDENTICAL=yes; else IDENTICAL=no; fi

  echo "$IMPL,$KILL_POINT,$repeat,$RUN_AT,$KILLED,$RESUME,$TOTAL,$MULTI,$RERUN,$INTERRUPTED,$RUNS,$ACCEPTED,$REJECTED,$IDENTICAL,$NOTES" >>"$CSV"
  log "  resume ${RESUME}s, more than one attempt: [${MULTI}], completed steps rerun: [${RERUN}], duplicates rejected: $REJECTED, identical: $IDENTICAL"
  cp "$WORK/app-$IMPL_PORT.log" "$HARNESS/work/logs-$IMPL-$KILL_POINT-$repeat.log" 2>/dev/null || true
done
