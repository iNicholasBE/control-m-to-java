#!/usr/bin/env python3
"""Batch window: run the chain end to end on a clean database and report wall-clock per step.

    python3 scripts/window.py --rows 200000 --repeats 3 --out ../results/spring-batch/window.csv
    python3 scripts/window.py --rows 2000000 --repeats 3 --out ../results/spring-batch/window.csv --append
"""
import argparse
import csv
import os

import chainlib as c

BUSINESS_DATE = "2026-10-23"
RUN_ID = f"NIGHTLY-CLOSE-{BUSINESS_DATE}-1"
FIELDS = ["rows", "file_mb", "repeat", "watcher_s", "extract_s", "scoring_wall_s", "partition_min_s",
          "partition_max_s", "join_s", "aggregate_s", "report_s", "deliver_s", "chain_total_s", "report_sha256"]


def seconds(sql):
    value = c.psql(sql)[0][0]
    return f"{float(value):.2f}" if value else ""


def step(name):
    return seconds(f"""SELECT extract(epoch FROM finished_at - started_at) FROM step_executions
                       WHERE run_id = '{RUN_ID}' AND step = '{name}'""")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rows", type=int, default=200_000)
    parser.add_argument("--portfolios", type=int, default=20, help="one scoring partition per portfolio")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--workdir", default=os.path.join(c.MODULE, "work", "window"))
    parser.add_argument("--out", required=True)
    parser.add_argument("--append", action="store_true")
    args = parser.parse_args()
    os.makedirs(args.workdir, exist_ok=True)

    with open(args.out, "a" if args.append else "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        if not args.append:
            writer.writeheader()
        for repeat in range(1, args.repeats + 1):
            c.reset_database()
            c.reset_workdir(args.workdir)
            positions = c.ensure_positions(args.workdir, BUSINESS_DATE, args.rows, args.portfolios)
            app = c.App(args.workdir, log_name=f"window-{args.rows}-{repeat}.log").start()
            app.wait_ready()
            app.post(BUSINESS_DATE)
            _, body = app.wait_terminal(BUSINESS_DATE, timeout=3600, poll=1)
            app.stop()
            assert body["state"] == "COMPLETED", body
            row = {
                "rows": args.rows, "file_mb": f"{os.path.getsize(positions) / 1e6:.0f}", "repeat": repeat,
                "watcher_s": step("watcher"), "extract_s": step("extract"),
                "scoring_wall_s": seconds(f"""SELECT extract(epoch FROM max(finished_at) - min(started_at))
                    FROM step_executions WHERE run_id = '{RUN_ID}' AND step LIKE 'scoring-partition-%'"""),
                "partition_min_s": seconds(f"""SELECT min(extract(epoch FROM finished_at - started_at))
                    FROM step_executions WHERE run_id = '{RUN_ID}' AND step LIKE 'scoring-partition-%'"""),
                "partition_max_s": seconds(f"""SELECT max(extract(epoch FROM finished_at - started_at))
                    FROM step_executions WHERE run_id = '{RUN_ID}' AND step LIKE 'scoring-partition-%'"""),
                "join_s": step("join"), "aggregate_s": step("aggregate"), "report_s": step("report"),
                "deliver_s": step("deliver"),
                "chain_total_s": seconds("SELECT extract(epoch FROM end_time - start_time) FROM batch_job_execution"),
                "report_sha256": c.sha256(os.path.join(args.workdir, "outbox", f"REPORT-{BUSINESS_DATE}-1.csv")),
            }
            writer.writerow(row)
            f.flush()
            print(row, flush=True)


if __name__ == "__main__":
    main()
