#!/usr/bin/env bash
# Samples pg_stat_activity once a second during a clean 1x run.
#
#   ./connections.sh <impl>
#
# jobrunr-pro runs twice: fixed-size workers (20, the default) and SelfLearningWorkerCapacityPolicy (at most 20)
# with the PrefetchQueue.
# Other implementations run once with their default configuration.
# Writes every sample to <results>/connections.csv and one line per run to <results>/connections-summary.csv.
source "$(dirname "$0")/lib.sh"

[[ $# -ge 1 ]] || die "usage: connections.sh <impl>"
load_impl "$1"
DATE="${BUSINESS_DATE:-2026-10-23}"
SAMPLES="$RESULTS_DIR/connections.csv"
SUMMARY="$RESULTS_DIR/connections-summary.csv"
[[ -f "$SAMPLES" ]] || echo "impl,variant,run_at_utc,t_s,total,active,idle,idle_in_transaction" >"$SAMPLES"
[[ -f "$SUMMARY" ]] || echo "impl,variant,run_at_utc,samples,max_total,max_active,avg_active_while_running,window_s" >"$SUMMARY"

sampled_run() {  # sampled_run <variant> [app args...]
  local variant="$1"; shift
  local run_at; run_at=$(iso_now)
  local tmp; tmp=$(mktemp)
  (
    start=$(date +%s)
    while true; do
      line=$(psql_db postgres "SELECT count(*), count(*) FILTER (WHERE state = 'active'), count(*) FILTER (WHERE state = 'idle'),
                                      count(*) FILTER (WHERE state LIKE 'idle in transaction%')
                                 FROM pg_stat_activity WHERE datname = '$DB_NAME'" 2>/dev/null || true)
      [[ -n "$line" ]] && echo "$IMPL,$variant,$run_at,$(( $(date +%s) - start )),$line" >>"$tmp"
      sleep 1
    done
  ) &
  local sampler=$!
  NO_BUILD=1 "$HARNESS/run_chain.sh" "$IMPL" "$DATE" --label "connections: $variant" -- "$@"
  kill "$sampler" 2>/dev/null; wait "$sampler" 2>/dev/null || true
  cat "$tmp" >>"$SAMPLES"
  local window; window=$(tail -1 "$RESULTS_DIR/windows.csv" | cut -d, -f7)
  awk -F, -v impl="$IMPL" -v variant="$variant" -v at="$run_at" -v window="$window" '
    { n++; if ($5 > mt) mt = $5; if ($6 > ma) ma = $6; if ($6 > 1) { sum += $6; busy++ } }
    END { printf "%s,%s,%s,%d,%d,%d,%.1f,%s\n", impl, variant, at, n, mt, ma, busy ? sum / busy : 0, window }' "$tmp" >>"$SUMMARY"
  rm -f "$tmp"
  log "connections ($variant): $(tail -1 "$SUMMARY")"
}

build_impl
if [[ "$IMPL" == "jobrunr-pro" ]]; then
  sampled_run "fixed-size-20"
  sampled_run "self-learning+prefetch-queue" \
    --jobrunr.background-job-server.worker-capacity-policy=self-learning \
    --jobrunr.background-job-server.work-distribution-strategy=prefetch-queue
elif [[ "$IMPL" == "jobrunr-oss" ]]; then
  sampled_run "4 workers, 20 partition threads"
else
  sampled_run "default"
fi
