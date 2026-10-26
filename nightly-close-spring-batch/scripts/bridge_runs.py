#!/usr/bin/env python3
"""Runs ../bridge-simulator/mainframe_side.py (the mainframe end of the bridge) against this module, for both
transports and two failure scenarios, on a clean database each time, and writes one CSV row per run.

    PG_CONTAINER=control-m-to-java-postgres-1 PG_USER=nightly PG_PASSWORD=nightly PG_PORT=5440 \
        python3 scripts/bridge_runs.py --out ../results/spring-batch/bridge.csv
"""
import argparse
import csv
import json
import os
import subprocess

import chainlib as c

SIMULATOR = os.path.abspath(os.path.join(c.MODULE, "..", "bridge-simulator", "mainframe_side.py"))
SCENARIOS = {
    "refire-during-run+refire-after-done": ["--refire-during-run", "--refire-after-done"],
    "drop-done-once": ["--drop-done-once"],
}
FIELDS = ["transport", "scenario", "rows", "simulator_exit_code", "triggers_sent", "run_ids_seen",
          "batch_job_instances", "batch_job_executions", "deliveries_accepted", "duplicate_deliveries_rejected",
          "done_file_present_at_end", "simulator_report"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rows", type=int, default=2_000_000)
    parser.add_argument("--business-date", default="2026-10-27")
    parser.add_argument("--workdir", default=os.path.join(c.MODULE, "work", "bridge"))
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    os.makedirs(args.workdir, exist_ok=True)
    port = 8190
    run_id = f"NIGHTLY-CLOSE-{args.business_date}-1"
    inbox = os.path.join(args.workdir, "bridge", "inbox")
    outbox = os.path.join(args.workdir, "bridge", "outbox")

    with open(args.out, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        writer.writeheader()
        for transport in ("file", "rest"):
            for scenario, flags in SCENARIOS.items():
                c.reset_database()
                c.reset_workdir(args.workdir)
                os.makedirs(inbox, exist_ok=True)
                os.makedirs(outbox, exist_ok=True)
                c.ensure_positions(args.workdir, args.business_date, args.rows)
                app = c.App(args.workdir, port=port, log_name=f"bridge-{transport}-{scenario}.log").start()
                app.wait_ready()
                report = os.path.join(args.workdir, f"sim-{transport}-{scenario}.json")
                command = ["python3", SIMULATOR, "--outbox", outbox, "--business-date", args.business_date,
                           "--status-url", f"http://localhost:{port}", "--json-report", report, "--timeout", "600"]
                command += ["--rest", f"http://localhost:{port}"] if transport == "rest" else ["--inbox", inbox]
                result = subprocess.run(command + flags, capture_output=True, text=True)
                print(result.stdout, flush=True)
                app.stop()
                sim = json.load(open(report)) if os.path.exists(report) else {}
                q = lambda sql: c.psql(sql)[0][0]
                row = {
                    "transport": transport, "scenario": scenario, "rows": args.rows,
                    "simulator_exit_code": result.returncode,
                    "triggers_sent": sim.get("triggers") if not isinstance(sim.get("triggers"), list) else len(sim["triggers"]),
                    "run_ids_seen": " ".join(sim.get("run_ids", [])),
                    "batch_job_instances": q("SELECT count(*) FROM batch_job_instance"),
                    "batch_job_executions": q("SELECT count(*) FROM batch_job_execution"),
                    "deliveries_accepted": q(f"SELECT count(*) FROM deliveries WHERE idempotency_key = '{run_id}'"),
                    "duplicate_deliveries_rejected": q(f"SELECT count(*) FROM delivery_rejections WHERE idempotency_key = '{run_id}'"),
                    "done_file_present_at_end": os.path.exists(os.path.join(outbox, f"NIGHTLY-CLOSE-{args.business_date}-DONE")),
                    "simulator_report": os.path.relpath(report, c.MODULE),
                }
                writer.writerow(row)
                f.flush()
                print(row, flush=True)


if __name__ == "__main__":
    main()
