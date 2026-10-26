#!/usr/bin/env python3
"""Exercises the interface contract end to end and writes one line per check.

    python3 scripts/contract_smoke.py --out ../results/spring-batch/contract-smoke.csv
"""
import argparse
import csv
import os
import threading
import time

import chainlib as c


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rows", type=int, default=200_000)
    parser.add_argument("--workdir", default=os.path.join(c.MODULE, "work", "smoke"))
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    os.makedirs(args.workdir, exist_ok=True)
    checks = []

    def check(name, expected, actual, detail=""):
        checks.append({"check": name, "expected": expected, "actual": actual, "pass": str(expected) == str(actual),
                       "detail": detail})
        print(checks[-1], flush=True)

    c.reset_database()
    c.reset_workdir(args.workdir, keep_inbox=False)
    c.ensure_positions(args.workdir, "2026-10-23", args.rows)
    app = c.App(args.workdir, log_name="smoke.log").start()
    app.wait_ready()

    code, body = app.post("2026-10-23")
    check("POST new identity", 202, code, body["state"])
    code, body = app.post("2026-10-23")
    check("POST same identity while running", 200, code, f"executionId {body['executionId']}")
    _, body = app.wait_terminal("2026-10-23")
    check("chain completes", "COMPLETED", body["state"],
          f"partitions {body['partitions']}, steps {len(body['steps'])}, sla {body['sla']}")
    code, body = app.post("2026-10-23")
    check("POST same identity after completion", 200, code, f"state {body['state']}, executions {body['executions']}")
    code, body = app.status("2026-10-23")
    check("GET status", 200, code, f"state {body['state']} startedAt {body['startedAt']} finishedAt {body['finishedAt']}")
    code, _ = app.status("2026-10-23", 7)
    check("GET unknown runNumber", 404, code)

    positions = os.path.join(args.workdir, "inbox", "POSITIONS-2026-10-23.csv")
    with open(positions, "a") as f:  # a corrected file arrives for the same business date
        f.write(open(positions).readlines()[-1])
    code, body = app.post("2026-10-23")
    check("POST same identity, different input hash", 409, code, body.get("reason", "") + f" executionId {body.get('executionId')}")
    code, body = app.post("2026-10-23", 2)
    check("POST runNumber=2 (explicit rerun)", 202, code)
    _, body = app.wait_terminal("2026-10-23", 2)
    check("rerun completes", "COMPLETED", body["state"])
    check("rerun report written", True, os.path.exists(os.path.join(args.workdir, "outbox", "REPORT-2026-10-23-2.csv")))
    keys = [r[0] for r in c.psql("SELECT idempotency_key FROM deliveries ORDER BY 1")]
    check("deliveries: one per run", 2, len(keys), " ".join(keys))

    # watcher: the chain is submitted before the file exists and waits for it
    code, body = app.post("2026-10-21")
    check("POST before the file exists", 202, code)
    time.sleep(3)
    _, body = app.status("2026-10-21")
    watcher = [s for s in body["steps"] if s["step"] == "watcher"]
    check("watcher waiting", "RUNNING", watcher[0]["status"] if watcher else None, f"state {body['state']}")
    dropped = time.time()
    c.ensure_positions(args.workdir, "2026-10-21", args.rows)
    _, body = app.wait_terminal("2026-10-21")
    check("chain completes after file arrives", "COMPLETED", body["state"], f"{time.time() - dropped:.1f}s after drop")

    # file bridge: READY flag in, DONE flag out
    c.ensure_positions(args.workdir, "2026-10-22", args.rows)
    os.makedirs(os.path.join(args.workdir, "bridge", "inbox"), exist_ok=True)
    ready = os.path.join(args.workdir, "bridge", "inbox", "NIGHTLY-CLOSE-2026-10-22-READY")
    open(ready, "w").close()
    t = time.time()
    c.wait_for_file(os.path.join(args.workdir, "bridge", "outbox", "NIGHTLY-CLOSE-2026-10-22-DONE"), timeout=120, poll=0.1)
    _, body = app.status("2026-10-22")
    check("bridge READY -> chain -> DONE", "COMPLETED", body["state"], f"DONE after {time.time() - t:.1f}s")
    _, trigger = c.psql("SELECT run_id, trigger_source FROM chain_runs WHERE run_id = 'NIGHTLY-CLOSE-2026-10-22-1'")[0]
    check("bridge run registered as", "bridge-file", trigger)

    # two simultaneous submits of a new identity: exactly one creates it
    c.ensure_positions(args.workdir, "2026-10-20", args.rows)
    codes = []
    threads = [threading.Thread(target=lambda: codes.append(app.post("2026-10-20")[0])) for _ in range(2)]
    [th.start() for th in threads]
    [th.join() for th in threads]
    check("two concurrent POSTs", "[200, 202]", str(sorted(codes)))
    app.wait_terminal("2026-10-20")
    instances = c.psql("""SELECT count(*) FROM batch_job_instance i JOIN batch_job_execution_params p
        ON p.job_execution_id = (SELECT min(job_execution_id) FROM batch_job_execution e WHERE e.job_instance_id = i.job_instance_id)
        WHERE p.parameter_name = 'businessDate' AND p.parameter_value = '2026-10-20'""")[0][0]
    check("job instances for 2026-10-20", 1, instances)
    app.stop()

    with open(args.out, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["check", "expected", "actual", "pass", "detail"])
        writer.writeheader()
        writer.writerows(checks)
    print("all passed" if all(ch["pass"] for ch in checks) else "FAILURES")


if __name__ == "__main__":
    main()
