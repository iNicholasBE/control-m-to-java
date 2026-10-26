#!/usr/bin/env python3
"""Rolling restart: two nodes, one database, one clustered Quartz scheduler. The cron trigger fires once in
the cluster and starts the chain on one node (the owner). Then, like a rolling deploy:
  1. SIGTERM the other node while the owner is in extract, start it again;
  2. SIGTERM the owner while scoring runs: it stops the job at the next chunk boundary and hands it over;
     the surviving node restarts the STOPPED execution; then start the old owner again.
Reports which steps ran more than once and what the regulator received.

    python3 scripts/rolling_restart.py --rows 2000000 --out ../results/spring-batch/rolling-restart.csv
"""
import argparse
import csv
import datetime as dt
import os
import shutil
import time

import chainlib as c

FIELDS = ["phase", "at_s", "detail"]


def node_of_owner(run_id):
    rows = c.psql(f"""SELECT n.node_name FROM chain_runs r JOIN chain_nodes n ON n.node_id = r.owner_node
                      WHERE r.run_id = '{run_id}'""")
    return rows[0][0] if rows else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rows", type=int, default=2_000_000)
    parser.add_argument("--workdir", default=os.path.join(c.MODULE, "work", "rolling"))
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    os.makedirs(args.workdir, exist_ok=True)

    business_date = dt.datetime.now(dt.timezone.utc).date().isoformat()
    run_id = f"NIGHTLY-CLOSE-{business_date}-1"
    report = os.path.join(args.workdir, "outbox", f"REPORT-{business_date}-1.csv")
    events = []

    def log(phase, detail=""):
        events.append({"phase": phase, "at_s": f"{time.time() - t0:.2f}", "detail": detail})
        print(events[-1], flush=True)

    # clean single-node run first, for the byte-identical check
    c.reset_database()
    c.reset_workdir(args.workdir)
    c.ensure_positions(args.workdir, business_date, args.rows)
    solo = c.App(args.workdir, port=8191, node_name="node-a", log_name="rolling-clean.log").start()
    solo.wait_ready()
    solo.post(business_date)
    solo.wait_terminal(business_date, timeout=3600, poll=1)
    solo.stop()
    shutil.copy(report, report + ".clean")
    clean_sha = c.sha256(report + ".clean")

    c.reset_database()
    c.reset_workdir(args.workdir)
    c.ensure_positions(args.workdir, business_date, args.rows)
    fire = dt.datetime.now(dt.timezone.utc).replace(microsecond=0) + dt.timedelta(seconds=25)
    cron = f"{fire.second} {fire.minute} {fire.hour} * * ?"
    t0 = time.time()
    nodes = {}
    for name, port in (("node-a", 8191), ("node-b", 8192)):
        nodes[name] = c.App(args.workdir, port=port, node_name=name, schedule=True, log_name=f"rolling-{name}.log",
                            extra_args=[f"--chain.schedule.cron={cron}"]).start()
    for app in nodes.values():
        app.wait_ready()
    log("both nodes up", f"cron '{cron}' UTC fires at {fire.isoformat()}")

    c.wait_for_file(os.path.join(args.workdir, "markers", "extract.entered"), timeout=120)
    owner = node_of_owner(run_id)
    other = "node-b" if owner == "node-a" else "node-a"
    log("quartz fired, chain in extract", f"owner {owner}")

    time.sleep(1)
    nodes[other].stop()
    log(f"{other} stopped (SIGTERM, not the owner)")
    nodes[other] = c.App(args.workdir, port=nodes[other].port, node_name=other, schedule=True,
                         log_name=f"rolling-{other}.log", extra_args=[f"--chain.schedule.cron={cron}"]).start()
    nodes[other].wait_ready()
    log(f"{other} back up")

    c.wait_for_file(os.path.join(args.workdir, "markers", "scoring-partition-1.entered"), timeout=600)
    time.sleep(2)
    log(f"SIGTERM to owner {owner} during scoring")
    nodes[owner].stop()
    log(f"{owner} exited")
    nodes[owner] = c.App(args.workdir, port=nodes[owner].port, node_name=owner, schedule=True,
                         log_name=f"rolling-{owner}.log", extra_args=[f"--chain.schedule.cron={cron}"]).start()
    nodes[owner].wait_ready()
    log(f"{owner} back up")

    _, body = nodes[other].wait_terminal(business_date, timeout=3600, poll=1)
    log("chain finished", f"state {body['state']}, executions {body['executions']}")
    for app in nodes.values():
        app.stop()

    rerun = c.psql(f"""SELECT step, max(attempt), string_agg(DISTINCT node, ' ') FROM step_executions
                       WHERE run_id = '{run_id}' GROUP BY step HAVING max(attempt) > 1 ORDER BY step""")
    by_node = c.psql(f"""SELECT node, count(*) FROM step_executions WHERE run_id = '{run_id}' GROUP BY node ORDER BY node""")
    executions = c.psql("""SELECT job_execution_id, status, to_char(start_time, 'HH24:MI:SS.MS'),
                           to_char(end_time, 'HH24:MI:SS.MS') FROM batch_job_execution ORDER BY 1""")
    stopped_steps = c.psql("""SELECT step_name, status, read_count FROM batch_step_execution
                              WHERE status <> 'COMPLETED' ORDER BY step_execution_id""")
    chunk_reads = c.psql("""SELECT job_execution_id, sum(read_count) FROM batch_step_execution
        WHERE step_name = 'extract' OR step_name LIKE 'scoring-worker:%' GROUP BY 1 ORDER BY 1""")
    quartz_fires = c.psql(f"SELECT count(*) FROM chain_runs WHERE trigger_source = 'quartz'")[0][0]
    accepted = c.psql(f"SELECT count(*) FROM deliveries WHERE idempotency_key = '{run_id}'")[0][0]
    rejected = c.psql(f"SELECT count(*) FROM delivery_rejections WHERE idempotency_key = '{run_id}'")[0][0]
    log("steps with more than one attempt", "; ".join(f"{s} x{a} on {n}" for s, a, n in rerun) or "none")
    log("step rows per node", "; ".join(f"{n}: {k}" for n, k in by_node))
    log("job executions", "; ".join(" ".join(e) for e in executions))
    log("non-completed step executions", "; ".join(" ".join(s) for s in stopped_steps) or "none")
    log("chunk rows read per job execution", "; ".join(f"exec {e}: {r}" for e, r in chunk_reads))
    log("quartz-triggered runs", quartz_fires)
    log("deliveries accepted / duplicates rejected", f"{accepted} / {rejected}")
    log("report identical to clean single-node run", str(c.sha256(report) == clean_sha))

    with open(args.out, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(events)


if __name__ == "__main__":
    main()
