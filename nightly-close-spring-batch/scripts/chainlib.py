"""Small helpers shared by the measurement scripts: start/kill the app, query Postgres, poll the contract.

Postgres is queried through `docker exec ... psql` so the scripts need nothing beyond python3 and docker.
Configuration comes from environment variables (defaults: this module's own Postgres container on 5434).
"""
import hashlib
import json
import os
import shutil
import signal
import subprocess
import time
import urllib.request
import urllib.error

MODULE = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
JAR = os.path.join(MODULE, "target", "nightly-close-spring-batch-1.0.0.jar")
JAVA = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "java") if os.environ.get("JAVA_HOME") else "java"
PG_CONTAINER = os.environ.get("PG_CONTAINER", "nightly-close-springbatch-pg")
PG_USER = os.environ.get("PG_USER", "postgres")
PG_PASSWORD = os.environ.get("PG_PASSWORD", "postgres")
PG_PORT = os.environ.get("PG_PORT", "5434")
PG_DB = "nightly_springbatch"
DB_URL = f"jdbc:postgresql://localhost:{PG_PORT}/{PG_DB}?reWriteBatchedInserts=true"

CHAIN_TABLES = ["step_executions", "chain_runs", "chain_nodes", "positions_staging", "risk_scores",
                "portfolio_aggregates", "deliveries", "delivery_rejections", "delivery_receipts"]
BATCH_TABLES = ["batch_step_execution_context", "batch_job_execution_context", "batch_step_execution",
                "batch_job_execution_params", "batch_job_execution", "batch_job_instance"]
QUARTZ_TABLES = ["qrtz_fired_triggers", "qrtz_cron_triggers", "qrtz_simple_triggers", "qrtz_simprop_triggers",
                 "qrtz_blob_triggers", "qrtz_triggers", "qrtz_job_details", "qrtz_calendars",
                 "qrtz_paused_trigger_grps", "qrtz_scheduler_state", "qrtz_locks"]


def psql(sql):
    out = subprocess.run(["docker", "exec", "-e", f"PGPASSWORD={PG_PASSWORD}", PG_CONTAINER, "psql", "-U", PG_USER,
                          "-d", PG_DB, "-At", "-F", "|", "-v", "ON_ERROR_STOP=1", "-c", sql],
                         check=True, capture_output=True, text=True).stdout.strip()
    return [line.split("|") for line in out.splitlines()] if out else []


def db_clock_offset():
    """DB clock minus host clock, in seconds (Docker's VM clock can drift from the host's)."""
    before = time.time()
    db_now = float(psql("SELECT extract(epoch FROM clock_timestamp())")[0][0])
    after = time.time()
    return db_now - (before + after) / 2


def reset_database():
    existing = {row[0] for row in psql("SELECT tablename FROM pg_tables WHERE schemaname = 'public'")}
    tables = [t for t in CHAIN_TABLES + BATCH_TABLES + QUARTZ_TABLES if t in existing]
    if tables:
        psql("TRUNCATE " + ", ".join(tables) + " CASCADE")


def reset_workdir(workdir, keep_inbox=True):
    for sub in ["outbox", "markers", "bridge"] + ([] if keep_inbox else ["inbox"]):
        shutil.rmtree(os.path.join(workdir, sub), ignore_errors=True)
    os.makedirs(os.path.join(workdir, "inbox"), exist_ok=True)
    shutil.copy(os.path.join(MODULE, "holidays.txt"), os.path.join(workdir, "holidays.txt"))


class App:
    """One JVM of the chain, started from `workdir` so ./inbox, ./outbox, ./markers resolve there."""

    def __init__(self, workdir, port=8190, node_name=None, extra_env=None, extra_args=None, log_name="app.log",
                 schedule=False):
        self.workdir, self.port, self.schedule = workdir, port, schedule
        self.node_name = node_name or f"node-{port}"
        self.extra_env, self.extra_args = extra_env or {}, extra_args or []
        self.log_path = os.path.join(workdir, log_name)
        self.proc = None
        self.started_at = None

    def start(self):
        env = dict(os.environ, PORT=str(self.port), DB_URL=DB_URL, DB_USER=PG_USER, DB_PASSWORD=PG_PASSWORD,
                   **self.extra_env)
        args = [JAVA, "-Xmx4g", "-jar", JAR, f"--chain.node-name={self.node_name}",
                f"--chain.schedule.enabled={str(self.schedule).lower()}"] + self.extra_args
        log = open(self.log_path, "a")
        self.started_at = time.time()
        self.proc = subprocess.Popen(args, cwd=self.workdir, env=env, stdout=log, stderr=subprocess.STDOUT)
        return self

    def wait_ready(self, timeout=60):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                urllib.request.urlopen(f"http://localhost:{self.port}/chains/nightly-close/1970-01-01", timeout=1)
            except urllib.error.HTTPError:
                return time.time()  # 404 = the app answers
            except Exception:
                time.sleep(0.05)
                continue
            return time.time()
        raise RuntimeError(f"app on {self.port} not ready, see {self.log_path}")

    def kill9(self):
        os.kill(self.proc.pid, signal.SIGKILL)
        self.proc.wait()
        return time.time()

    def stop(self, timeout=90):
        if self.proc and self.proc.poll() is None:
            self.proc.send_signal(signal.SIGTERM)
            try:
                self.proc.wait(timeout)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait()

    def post(self, business_date, run_number=1):
        return http("POST", f"http://localhost:{self.port}/chains/nightly-close?businessDate={business_date}&runNumber={run_number}")

    def status(self, business_date, run_number=1):
        return http("GET", f"http://localhost:{self.port}/chains/nightly-close/{business_date}?runNumber={run_number}")

    def wait_terminal(self, business_date, run_number=1, timeout=1800, poll=0.2):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                code, body = self.status(business_date, run_number)
                if code == 200 and body["state"] in ("COMPLETED", "FAILED", "ABANDONED"):
                    return time.time(), body
            except Exception:
                pass
            time.sleep(poll)
        raise RuntimeError("chain did not finish in time")


def http(method, url):
    request = urllib.request.Request(url, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.loads(response.read() or b"null")
    except urllib.error.HTTPError as e:
        body = e.read()
        return e.code, (json.loads(body) if body else None)


def wait_for_file(path, timeout=600, poll=0.005, newer_than=0.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if os.path.getmtime(path) >= newer_than:
                return time.time()
        except FileNotFoundError:
            pass
        time.sleep(poll)
    raise RuntimeError(f"timed out waiting for {path}")


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


GENERATOR = os.path.abspath(os.path.join(MODULE, "..", "harness", "gen_positions.py"))


def ensure_positions(workdir, business_date, rows):
    """Generate the positions file with the shared harness generator (seed 42), cached per (date, rows)."""
    cache = os.path.join(MODULE, "work", "cache", str(rows))
    os.makedirs(cache, exist_ok=True)
    cached = os.path.join(cache, f"POSITIONS-{business_date}.csv")
    if not os.path.exists(cached):
        subprocess.run(["python3", GENERATOR, "--date", business_date, "--rows", str(rows), "--seed", "42",
                        "--out-dir", cache], check=True, stdout=subprocess.DEVNULL)
    target = os.path.join(workdir, "inbox", f"POSITIONS-{business_date}.csv")
    if not os.path.exists(target) or sha256(target) != sha256(cached):
        shutil.copy(cached, target + ".part")
        os.replace(target + ".part", target)
    return target
