#!/usr/bin/env bash
# Runs the nightly close once, clean, and records the batch window.
#
#   ./run_chain.sh <impl> <businessDate> [--scale 1|10] [--transport file|rest] [--label text] [--save-reference]
#
# impl: jobrunr-oss | jobrunr-pro | springbatch (see impls/). Appends one row to <results>/windows.csv.
source "$(dirname "$0")/lib.sh"

[[ $# -ge 2 ]] || die "usage: run_chain.sh <impl> <businessDate> [--scale 1|10] [--transport file|rest] [--label text] [--save-reference]"
load_impl "$1"; DATE="$2"; shift 2
SCALE=1; TRANSPORT=file; LABEL=""; SAVE_REFERENCE=0; EXTRA_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --scale) SCALE="$2"; shift 2 ;;
    --transport) TRANSPORT="$2"; shift 2 ;;
    --label) LABEL="$2"; shift 2 ;;
    --save-reference) SAVE_REFERENCE=1; shift ;;
    --) shift; EXTRA_ARGS=("$@"); break ;;
    *) die "unknown option $1" ;;
  esac
done

postgres_up
build_impl
POSITIONS=$(positions_file "$DATE" "$SCALE")
WORK="$HARNESS/work/$IMPL"
reset_db
prepare_workdir "$WORK" "$POSITIONS"

PID=$(start_app "$WORK" "$IMPL_PORT" "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}")
trap 'stop_app "$PID"' EXIT
wait_ready "$IMPL_PORT" "$PID"

log "$IMPL: triggering $DATE at ${SCALE}x via $TRANSPORT"
RUN_AT=$(iso_now)
T0=$(now_ms)
trigger "$WORK" "$IMPL_PORT" "$DATE" "$TRANSPORT"
wait_for_file "$WORK/bridge/outbox/NIGHTLY-CLOSE-$DATE-DONE" 3600 || die "no DONE file within an hour, see $WORK/app-$IMPL_PORT.log"
T1=$(now_ms)
WINDOW=$(secs_between "$T0" "$T1")

RUN_ID=$(run_id_of "$DATE")
STEPS=$(step_seconds "$RUN_ID")
ROWS=$(( $(wc -l <"$POSITIONS") - 1 ))
REPORT="$WORK/outbox/REPORT-$DATE-1.csv"
if [[ "$SAVE_REFERENCE" == 1 ]]; then
  mkdir -p "$HARNESS/work/reference/$IMPL"
  cp "$REPORT" "$HARNESS/work/reference/$IMPL/"
fi

CSV="$RESULTS_DIR/windows.csv"
[[ -f "$CSV" ]] || echo "impl,scale,rows,business_date,transport,run_at_utc,window_s,chain_s,watcher_s,extract_s,scoring_s,join_gap_s,aggregate_s,report_s,deliver_s,report_sha256,jdk,postgres,label" >"$CSV"
echo "$IMPL,$SCALE,$ROWS,$DATE,$TRANSPORT,$RUN_AT,$WINDOW,$(value_of "$STEPS" chain),$(value_of "$STEPS" watcher),$(value_of "$STEPS" extract),$(value_of "$STEPS" scoring),$(value_of "$STEPS" join_gap),$(value_of "$STEPS" aggregate),$(value_of "$STEPS" report),$(value_of "$STEPS" deliver),$(shasum -a 256 "$REPORT" | cut -c1-64),$(jdk_version),$(postgres_version),$LABEL" >>"$CSV"

log "$IMPL ${SCALE}x window ${WINDOW}s (trigger to DONE file)"
printf '%s\n' "$STEPS" | sed 's/^/  /' >&2
