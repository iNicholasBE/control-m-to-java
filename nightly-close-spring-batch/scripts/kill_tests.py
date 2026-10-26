#!/usr/bin/env python3
"""Kill tests: for each kill point, start the chain, kill -9 the JVM when the step marker appears (plus a
small offset for steps long enough to be killed mid-way), start a fresh JVM, let crash recovery restart the
chain, and check what ran twice, what the regulator saw and whether the report is byte-identical.

    python3 scripts/kill_tests.py --rows 200000 --repeats 3 --out ../results/spring-batch/kill-tests.csv
"""
import argparse
import csv
import os
import shutil
import time

import chainlib as c

BUSINESS_DATE = "2026-10-23"
RUN_ID = f"NIGHTLY-CLOSE-{BUSINESS_DATE}-1"
DELIVER_HOOKS = {"CHAIN_DELIVER_SLEEP_BEFORE_CALL": "2000ms", "CHAIN_DELIVER_SLEEP_AFTER_CALL": "2000ms"}

# kill point -> (marker file, offset after the marker appears in seconds, test hooks)
SCENARIOS = {
    "extract": ("extract.entered", 0.8, {}),
    "scoring-partition-3": ("scoring-partition-3.entered", 0.3, {}),
    "aggregate": ("aggregate.entered", 0.0, {}),
    "report": ("report.entered", 0.0, {}),
    "deliver-before-call": ("deliver-before-call.entered", 0.0, DELIVER_HOOKS),
    "deliver-after-call-before-record": ("deliver-after-call-before-record.entered", 0.0, DELIVER_HOOKS),
}

FIELDS = ["kill_point", "repeat", "rows", "kill_offset_s", "interrupted_steps", "resume_s", "app_ready_s",
          "recovered_to_completed_s", "steps_with_more_than_one_attempt", "steps_rerun",
          "chunk_rows_read_before_kill", "chunk_rows_read_after_restart", "batch_job_executions",
          "deliveries_accepted", "duplicate_deliveries_rejected", "delivery_receipts", "report_identical",
          "final_state"]


def compact(steps):
    partitions = [s for s in steps if s.startswith("scoring-partition-")]
    others = [s for s in steps if not s.startswith("scoring-partition-")]
    if len(partitions) > 3:
        others.append(f"scoring-partition-* (x{len(partitions)})")
    else:
        others.extend(partitions)
    return " ".join(others)


def run_clean(workdir, rows):
    c.reset_database()
    c.reset_workdir(workdir)
    c.ensure_positions(workdir, BUSINESS_DATE, rows)
    app = c.App(workdir, log_name="clean.log").start()
    app.wait_ready()
    app.post(BUSINESS_DATE)
    _, body = app.wait_terminal(BUSINESS_DATE)
    app.stop()
    assert body["state"] == "COMPLETED", body
    report = os.path.join(workdir, "outbox", f"REPORT-{BUSINESS_DATE}-1.csv")
    baseline = os.path.join(workdir, "baseline-report.csv")
    shutil.copy(report, baseline)
    return c.sha256(baseline)


def run_scenario(workdir, rows, name, repeat, baseline_sha):
    marker, offset, hooks = SCENARIOS[name]
    c.reset_database()
    c.reset_workdir(workdir)
    c.ensure_positions(workdir, BUSINESS_DATE, rows)
    clock = c.db_clock_offset()

    first = c.App(workdir, extra_env=hooks, log_name=f"kill-{name}-{repeat}-a.log").start()
    first.wait_ready()
    first.post(BUSINESS_DATE)
    c.wait_for_file(os.path.join(workdir, "markers", marker))
    time.sleep(offset)
    killed_at = first.kill9()

    second = c.App(workdir, extra_env=hooks, log_name=f"kill-{name}-{repeat}-b.log").start()
    restarted_at = second.started_at
    ready_at = second.wait_ready()
    done_at, body = second.wait_terminal(BUSINESS_DATE)
    second.stop()

    killed_db = killed_at + clock
    interrupted = [r[0] for r in c.psql(
        f"SELECT step FROM step_executions WHERE run_id = '{RUN_ID}' AND outcome = 'INTERRUPTED' ORDER BY step")]
    resumed = c.psql(f"""SELECT extract(epoch FROM min(started_at)) FROM step_executions
                         WHERE run_id = '{RUN_ID}' AND started_at > to_timestamp({killed_db})""")[0][0]
    rerun = [r[0] for r in c.psql(f"""SELECT step FROM step_executions WHERE run_id = '{RUN_ID}'
                                      GROUP BY step HAVING max(attempt) > 1 ORDER BY step""")]
    executions = c.psql(f"""SELECT count(*) FROM batch_job_execution e
                            JOIN batch_job_instance i USING (job_instance_id)""")[0][0]
    accepted = c.psql(f"SELECT count(*) FROM deliveries WHERE idempotency_key = '{RUN_ID}'")[0][0]
    rejected = c.psql(f"SELECT count(*) FROM delivery_rejections WHERE idempotency_key = '{RUN_ID}'")[0][0]
    receipts = c.psql(f"SELECT count(*) FROM delivery_receipts WHERE idempotency_key = '{RUN_ID}'")[0][0]
    # rows the chunked steps (extract + scoring partitions) committed per job execution: before + after = rows
    # read once each; anything above 2 x rows in total would mean committed chunks were read again
    chunk_reads = [int(r[1]) for r in c.psql("""SELECT job_execution_id, sum(read_count) FROM batch_step_execution
        WHERE step_name = 'extract' OR step_name LIKE 'scoring-worker:%' GROUP BY 1 ORDER BY 1""")]
    report = os.path.join(workdir, "outbox", f"REPORT-{BUSINESS_DATE}-1.csv")
    identical = os.path.exists(report) and c.sha256(report) == baseline_sha
    resume_s = (float(resumed) - clock - restarted_at) if resumed else None
    return {
        "kill_point": name, "repeat": repeat, "rows": rows, "kill_offset_s": offset,
        "interrupted_steps": compact(interrupted),
        "resume_s": f"{resume_s:.2f}" if resume_s is not None else "",
        "app_ready_s": f"{ready_at - restarted_at:.2f}",
        "recovered_to_completed_s": f"{done_at - restarted_at:.2f}",
        "steps_with_more_than_one_attempt": len(rerun), "steps_rerun": compact(rerun),
        "chunk_rows_read_before_kill": chunk_reads[0] if chunk_reads else 0,
        "chunk_rows_read_after_restart": sum(chunk_reads[1:]),
        "batch_job_executions": executions, "deliveries_accepted": accepted,
        "duplicate_deliveries_rejected": rejected, "delivery_receipts": receipts,
        "report_identical": identical, "final_state": body["state"],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rows", type=int, default=200_000)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--only", nargs="*", default=list(SCENARIOS))
    parser.add_argument("--workdir", default=os.path.join(c.MODULE, "work", "kill"))
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    os.makedirs(args.workdir, exist_ok=True)

    baseline_sha = run_clean(args.workdir, args.rows)
    print(f"clean run report sha256 {baseline_sha}", flush=True)
    with open(args.out, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        writer.writeheader()
        for name in args.only:
            for repeat in range(1, args.repeats + 1):
                row = run_scenario(args.workdir, args.rows, name, repeat, baseline_sha)
                writer.writerow(row)
                f.flush()
                print(row, flush=True)


if __name__ == "__main__":
    main()
