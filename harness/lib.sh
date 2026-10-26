# Shared helpers for the harness scripts. Source it; do not run it.
set -euo pipefail

HARNESS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HARNESS/.." && pwd)"
PG_PORT="${PG_PORT:-5440}"
JAVA_OPTS="${JAVA_OPTS:--Xmx2g}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25)}"

log() { printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }
secs_between() { python3 -c "print(f'{($2 - $1) / 1000:.1f}')"; }
iso_now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# load_impl <impl>: reads impls/<impl>.conf (IMPL_* variables and the impl_args function).
load_impl() {
  local conf="$HARNESS/impls/$1.conf"
  [[ -f "$conf" ]] || die "unknown implementation '$1' (no $conf)"
  IMPL="$1"
  # shellcheck source=/dev/null
  source "$conf"
  mkdir -p "$RESULTS_DIR"
}

# with_timeout <seconds> <command...>: macOS has no timeout(1). A hung Docker call fails the run instead of stalling it.
with_timeout() {
  local secs="$1"; shift
  "$@" &
  local pid=$!
  ( sleep "$secs"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
  local watchdog=$!
  local rc=0
  wait "$pid" || rc=$?
  { kill "$watchdog" && wait "$watchdog"; } 2>/dev/null || true
  return "$rc"
}

# Docker Desktop's disk image grows with every load and the VM stalls when the host disk is full.
require_free_disk() {
  local need_gb="${MIN_FREE_GB:-4}" free_gb
  free_gb=$(df -g "$HARNESS" | awk 'NR == 2 { print $4 }')
  (( free_gb >= need_gb )) || die "only ${free_gb} GB free on the host disk, need ${need_gb} GB (MIN_FREE_GB); free space first"
}

compose() { docker compose -f "$HARNESS/docker-compose.yml" "$@"; }
postgres_up() { require_free_disk; with_timeout 180 compose up -d --wait >/dev/null 2>&1 || die "Postgres did not come up (is Docker running, is the disk full?)"; }
psql_db() { with_timeout "${PSQL_TIMEOUT:-120}" compose exec -T postgres psql -U nightly -d "$1" -v ON_ERROR_STOP=1 -At -F, -c "$2"; }
sql() { psql_db "$DB_NAME" "$1"; }

reset_db() {
  psql_db postgres "DROP DATABASE IF EXISTS $DB_NAME WITH (FORCE)" >/dev/null
  psql_db postgres "CREATE DATABASE $DB_NAME OWNER nightly" >/dev/null
}

build_impl() {
  if [[ "${NO_BUILD:-0}" == "1" && -f "$IMPL_JAR" ]]; then return; fi
  log "building $IMPL"
  (eval "$IMPL_BUILD") >&2 || die "build failed for $IMPL"
}

# positions_file <date> <scale>: generates once, then reused (hard-linked into each work dir).
positions_file() {
  local date="$1" scale="$2" rows=$((200000 * $2))
  local dir="$HARNESS/data/${scale}x"
  local file="$dir/POSITIONS-$date.csv"
  if [[ ! -f "$file" ]]; then
    log "generating $rows positions for $date"
    python3 "$HARNESS/gen_positions.py" --date "$date" --rows "$rows" --out-dir "$dir" >/dev/null
  fi
  echo "$file"
}

# prepare_workdir <dir>: a clean working directory, the app runs with it as cwd.
prepare_workdir() {
  local dir="$1"
  rm -rf "$dir"
  mkdir -p "$dir/inbox" "$dir/outbox" "$dir/markers" "$dir/bridge/inbox" "$dir/bridge/outbox"
  cp "$HARNESS/holidays.txt" "$dir/holidays.txt"
}

# drop_positions <dir> <positions file>: the file arrives in the inbox (a hard link, so it appears atomically).
drop_positions() {
  ln "$2" "$1/inbox/" 2>/dev/null || cp "$2" "$1/inbox/"
}

# start_app <workdir> <port> [extra args...]: prints the pid. The JVM is started directly, so the pid is the JVM's.
start_app() {
  local dir="$1" port="$2"; shift 2
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    die "port $port is already in use; stop that process first (the harness never kills processes it did not start)"
  fi
  # impl_args prints one argument per line, so values with spaces (a cron expression) survive.
  local args=() arg
  while IFS= read -r arg; do args+=("$arg"); done < <(impl_args "$port")
  # shellcheck disable=SC2086
  (cd "$dir" && exec "$JAVA_HOME/bin/java" $JAVA_OPTS -jar "$IMPL_JAR" "${args[@]}" "$@" >>"$dir/app-$port.log" 2>&1) &
  echo $!
}

wait_ready() {
  local port="$1" pid="$2" deadline=$(( $(date +%s) + 120 ))
  until curl -s -o /dev/null "http://localhost:$port/chains/nightly-close/2000-01-03"; do
    kill -0 "$pid" 2>/dev/null || die "app on port $port exited during startup"
    (( $(date +%s) < deadline )) || die "app on port $port not ready after 120s"
    sleep 0.2
  done
}

stop_app() {
  local pid="$1"
  kill -0 "$pid" 2>/dev/null || return 0
  kill -TERM "$pid"
  for _ in $(seq 1 600); do kill -0 "$pid" 2>/dev/null || return 0; sleep 0.1; done
  log "pid $pid ignored SIGTERM for 60s, killing"
  kill -9 "$pid" 2>/dev/null || true
}

wait_for_file() {
  local file="$1" timeout="$2" deadline=$(( $(date +%s) + $2 ))
  until [[ -e "$file" ]]; do
    (( $(date +%s) < deadline )) || return 1
    sleep 0.1
  done
}

# trigger <workdir> <port> <date> <transport> [runNumber]: the positions file arrives ($POSITIONS), then the
# mainframe signals through the bridge. The inbox watcher may start the run from the file before the signal lands.
trigger() {
  local dir="$1" port="$2" date="$3" transport="$4" run="${5:-1}"
  drop_positions "$dir" "$POSITIONS"
  case "$transport" in
    file)
      printf 'runNumber=%s\n' "$run" >"$dir/bridge/inbox/.NIGHTLY-CLOSE-$date-READY.tmp"
      mv "$dir/bridge/inbox/.NIGHTLY-CLOSE-$date-READY.tmp" "$dir/bridge/inbox/NIGHTLY-CLOSE-$date-READY" ;;
    rest)
      curl -s -o /dev/null -w '%{http_code}' -X POST "http://localhost:$port/chains/nightly-close?businessDate=$date&runNumber=$run" | grep -qE '^(200|202)$' \
        || die "POST was not accepted" ;;
    *) die "unknown transport $transport" ;;
  esac
}

run_id_of() { sql "SELECT run_id FROM chain_runs WHERE business_date = '$1' AND run_number = ${2:-1}"; }

jdk_version() { "$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed 's/.*version "\(.*\)".*/\1/'; }

# step_seconds <runId>: "step,seconds" lines, partitions folded into "scoring", plus "join_gap"
# (last partition finished -> aggregate started: what the join costs in wall clock).
step_seconds() {
  sql "WITH s AS (
         SELECT CASE WHEN step LIKE 'scoring-partition-%' THEN 'scoring' ELSE step END AS step, started_at, finished_at
           FROM step_executions WHERE run_id = '$1')
       SELECT step, round(extract(epoch FROM max(finished_at) - min(started_at))::numeric, 2) FROM s GROUP BY step
       UNION ALL
       SELECT 'join_gap', round(extract(epoch FROM
                (SELECT min(started_at) FROM s WHERE step = 'aggregate') - (SELECT max(finished_at) FROM s WHERE step = 'scoring'))::numeric, 2)
       UNION ALL
       SELECT 'chain', round(extract(epoch FROM finished_at - submitted_at)::numeric, 2) FROM chain_runs WHERE run_id = '$1'"
}

# value_of <lines> <key>
value_of() { awk -F, -v k="$2" '$1 == k { print $2 }' <<<"$1"; }

postgres_version() { psql_db postgres "SHOW server_version" | awk '{print $1}'; }

# hop_seconds <runId>: the wait between one step ending and the next starting, in flow order.
hop_seconds() {
  sql "WITH s AS (
         SELECT CASE WHEN step LIKE 'scoring-partition-%' THEN 'scoring' ELSE step END AS step,
                min(started_at) AS started, max(finished_at) AS finished
           FROM step_executions WHERE run_id = '$1' GROUP BY 1),
       g AS (SELECT step, started, finished FROM s UNION ALL
             SELECT 'done', finished_at, finished_at FROM chain_runs WHERE run_id = '$1')
       SELECT string_agg(coalesce(round(extract(epoch FROM b.started - a.finished)::numeric, 2)::text, ''), ',' ORDER BY o.n)
         FROM (VALUES (1, 'watcher', 'extract'), (2, 'extract', 'scoring'), (3, 'scoring', 'aggregate'),
                      (4, 'aggregate', 'report'), (5, 'report', 'deliver'), (6, 'deliver', 'done')) o(n, f, t)
         LEFT JOIN g a ON a.step = o.f LEFT JOIN g b ON b.step = o.t"
}
