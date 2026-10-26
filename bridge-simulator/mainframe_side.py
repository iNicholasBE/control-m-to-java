#!/usr/bin/env python3
"""Play the mainframe (Control-M) side of the nightly-close bridge.

Fires NIGHTLY-CLOSE-<businessDate>-READY at the Java side, either as an empty
file in the inbox or as POST /chains/nightly-close?businessDate=..., then waits
for NIGHTLY-CLOSE-<businessDate>-DONE in the outbox and polls
GET /chains/nightly-close/<businessDate> to see how many chain runs the Java
side started for that key.

Failure cases it can inject:
  --refire-during-run   send the same READY again while the chain is running
  --refire-after-done   send it again after DONE appeared
  --drop-done-once      delete the first DONE (a lost completion message); the
                        mainframe then re-sends READY after --redrive-after seconds
                        and has to learn the outcome from the status endpoint
If the Java side never picks the trigger up, the run ends with exit code 3.

Exit codes: 0 ok, 1 more than one run started for the key, 2 no completion
before --timeout, 3 the Java side never acknowledged the trigger.

Standard library only. Example:
  python3 mainframe_side.py --inbox ./bridge/inbox --outbox ./bridge/outbox \\
      --business-date 2026-10-27 --refire-during-run --refire-after-done
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date
from pathlib import Path

TERMINAL_STATES = {"DONE", "SUCCEEDED", "COMPLETED", "FAILED", "STOPPED", "ABANDONED"}


class Report:
    def __init__(self):
        self.t0 = time.monotonic()
        self.events: list[str] = []
        self.triggers: list[dict] = []
        self.run_ids: list[str] = []
        self.started_ats: list[str] = []
        self.execution_ids: list[str] = []
        self.max_executions = 0
        self.last_state = None
        self.ack_at = None
        self.done_at = None
        self.done_dropped_at = None
        self.status_errors = 0
        self.status_not_found = 0
        self.status_ok = 0

    def now(self) -> float:
        return time.monotonic() - self.t0

    def log(self, msg: str) -> None:
        line = f"[{self.now():7.1f}s] {msg}"
        self.events.append(line)
        print(line, flush=True)

    @staticmethod
    def _add(lst, value):
        if value not in (None, "") and str(value) not in lst:
            lst.append(str(value))


def http(method: str, url: str, timeout: float = 5.0):
    req = urllib.request.Request(url, method=method, headers={"Accept": "application/json"})
    if method == "POST":
        req.data = b""
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", "replace")
            return resp.status, body
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


class MainframeSide:
    def __init__(self, args):
        self.a = args
        self.key = f"NIGHTLY-CLOSE-{args.business_date}"
        self.inbox = Path(args.inbox)
        self.outbox = Path(args.outbox)
        self.ready = self.inbox / f"{self.key}-READY"
        self.done = self.outbox / f"{self.key}-DONE"
        self.status_url = None
        base = args.status_url if args.status_url is not None else (args.rest or "http://localhost:8080")
        if base:
            self.status_url = f"{base.rstrip('/')}/chains/nightly-close/{args.business_date}"
        self.r = Report()

    # ------------------------------------------------------------------ triggers
    def fire(self, why: str) -> None:
        if self.a.rest:
            url = (f"{self.a.rest.rstrip('/')}/chains/nightly-close?"
                   f"{urllib.parse.urlencode({'businessDate': self.a.business_date})}")
            try:
                code, body = http("POST", url)
            except OSError as e:
                code, body = None, str(e)
            outcome = ""
            try:
                parsed = json.loads(body)
                outcome = parsed.get("outcome") or parsed.get("state") or ""
                Report._add(self.r.run_ids, parsed.get("runId"))
            except (ValueError, AttributeError):
                pass
            self.r.triggers.append({"at": round(self.r.now(), 1), "why": why, "via": "rest", "http": code,
                                    "outcome": outcome})
            self.r.log(f"fire ({why}): POST -> HTTP {code} {outcome}")
            if code and 200 <= code < 300 and self.r.ack_at is None:
                self.r.ack_at = self.r.now()
        else:
            self.inbox.mkdir(parents=True, exist_ok=True)
            tmp = self.inbox / f".{self.key}-READY.{os.getpid()}.tmp"
            tmp.write_bytes(b"")
            os.replace(tmp, self.ready)  # atomic: the Java side never sees a half-written file
            self.r.triggers.append({"at": round(self.r.now(), 1), "why": why, "via": "file"})
            self.r.log(f"fire ({why}): wrote {self.ready}")

    # ------------------------------------------------------------------ observation
    def poll_status(self):
        if not self.status_url:
            return None
        try:
            code, body = http("GET", self.status_url, timeout=3.0)
        except OSError:
            self.r.status_errors += 1
            return None
        if code != 200:
            self.r.status_not_found += 1 if code == 404 else 0
            return None
        try:
            s = json.loads(body)
        except ValueError:
            return None
        self.r.status_ok += 1
        before = len(self.r.run_ids)
        Report._add(self.r.run_ids, s.get("runId"))
        Report._add(self.r.started_ats, s.get("startedAt"))
        Report._add(self.r.execution_ids, s.get("executionId"))
        if isinstance(s.get("executions"), int):
            self.r.max_executions = max(self.r.max_executions, s["executions"])
        state = s.get("state")
        if state != self.r.last_state:
            self.r.log(f"status: runId={s.get('runId')} state={state}")
            self.r.last_state = state
        if len(self.r.run_ids) > before > 0:
            self.r.log(f"NEW RUN ID for the same key: {s.get('runId')}")
        if self.r.ack_at is None:
            self.r.ack_at = self.r.now()
            self.r.log("acknowledged: the Java side reports a run for this key")
        return s

    def ready_consumed(self) -> bool:
        return not self.ready.exists()

    # ------------------------------------------------------------------ main loop
    def run(self) -> int:
        a, r = self.a, self.r
        stale_done = self.done.exists()
        if stale_done:
            r.log(f"note: {self.done} already exists before the trigger; it is ignored until it is rewritten")
        stale_mtime = self.done.stat().st_mtime if stale_done else None
        self.fire("initial")
        refired_during = refired_after = dropped = redriven = False
        settle_until = None
        drop_redrive_at = None
        deadline = time.monotonic() + a.timeout
        while time.monotonic() < deadline:
            status = self.poll_status()
            state = (status or {}).get("state")
            if r.ack_at is None and not a.rest and self.ready_consumed():
                r.ack_at = r.now()
                r.log("acknowledged: the Java side picked up the READY file")
            if r.ack_at is None and r.now() > a.ack_timeout:
                r.log(f"no acknowledgement after {a.ack_timeout}s: the Java side never picked up the trigger")
                return self.finish(3)

            done_now = self.done.exists() and (stale_mtime is None or self.done.stat().st_mtime > stale_mtime)
            terminal = state in TERMINAL_STATES

            if a.refire_during_run and not refired_during and r.ack_at is not None and not done_now and not terminal \
                    and (a.rest or self.ready_consumed()):
                self.fire("re-fire during run")
                refired_during = True

            if done_now and r.done_at is None:
                if a.drop_done_once and not dropped:
                    self.done.unlink()
                    dropped = True
                    r.done_dropped_at = r.now()
                    drop_redrive_at = r.now() + a.redrive_after
                    r.log("DONE arrived and was dropped (simulated lost completion)")
                    continue
                r.done_at = r.now()
                r.log(f"DONE arrived: {self.done}")
            if dropped and not redriven and drop_redrive_at is not None and r.now() >= drop_redrive_at:
                self.fire("re-drive after lost DONE")
                redriven = True
            if dropped and redriven and r.done_at is None and terminal and r.now() >= drop_redrive_at + a.settle:
                r.done_at = r.now()
                r.log(f"completion learned from the status endpoint (state {state}); "
                      "no new DONE file was written for the re-drive")

            if r.done_at is not None:
                if a.refire_after_done and not refired_after:
                    self.fire("re-fire after DONE")
                    refired_after = True
                    settle_until = r.now() + a.settle
                elif settle_until is None:
                    settle_until = r.now() + (a.settle if (refired_during or redriven) else 0)
                if r.now() >= settle_until:
                    return self.finish(0)
            time.sleep(a.poll)
        r.log(f"timeout after {a.timeout}s without completion")
        return self.finish(2)

    def finish(self, code: int) -> int:
        r = self.r
        runs = len(r.run_ids)
        if runs > 1:
            code = 1
        print()
        print("=" * 66)
        print(f"bridge report for {self.key}")
        print("=" * 66)
        print(f"transport               {'REST ' + self.a.rest if self.a.rest else 'files ' + str(self.inbox)}")
        print(f"triggers sent           {len(r.triggers)}: " + ", ".join(t['why'] for t in r.triggers))
        print(f"acknowledged            {'yes, after %.1fs' % r.ack_at if r.ack_at is not None else 'NO'}")
        if self.status_url:
            if r.status_ok:
                print(f"chain starts (run ids)  {runs}: {', '.join(r.run_ids)}")
                if r.execution_ids or r.max_executions:
                    print(f"executions seen         {max(len(r.execution_ids), r.max_executions)}"
                          " (Spring Batch job executions; >1 means a restart or a duplicate start)")
                print(f"last state              {r.last_state}")
            elif r.status_not_found:
                print("chain starts (run ids)  0: the status endpoint knows no run for this key (HTTP 404)")
            else:
                print(f"chain starts (run ids)  unknown: {self.status_url} did not answer")
        else:
            print("chain starts (run ids)  not polled (--status-url '')")
        if r.done_dropped_at is not None:
            print(f"DONE                    dropped once at {r.done_dropped_at:.1f}s; "
                  f"{'completion confirmed at %.1fs' % r.done_at if r.done_at is not None else 'never confirmed'}")
        else:
            print(f"DONE arrived            {'yes, at %.1fs' % r.done_at if r.done_at is not None else 'NO'}")
        print(f"elapsed                 {r.now():.1f}s")
        verdict = {0: "OK: one run for the key",
                   1: f"FAIL: {runs} runs started for the same key",
                   2: "FAIL: no completion before the timeout",
                   3: "FAIL: the Java side never acknowledged the trigger"}[code]
        print(f"verdict                 {verdict}")
        if self.a.json_report:
            Path(self.a.json_report).write_text(json.dumps({
                "key": self.key, "exit_code": code, "triggers": r.triggers, "run_ids": r.run_ids,
                "started_at_values": r.started_ats, "execution_ids": r.execution_ids,
                "ack_seconds": r.ack_at, "done_seconds": r.done_at, "done_dropped_seconds": r.done_dropped_at,
                "last_state": r.last_state, "elapsed_seconds": round(r.now(), 1), "events": r.events,
            }, indent=2))
        return code


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--inbox", default="./bridge/inbox")
    ap.add_argument("--outbox", default="./bridge/outbox")
    ap.add_argument("--business-date", required=True, type=lambda s: date.fromisoformat(s).isoformat())
    ap.add_argument("--rest", default=None, help="base URL; trigger with POST instead of a file")
    ap.add_argument("--status-url", default=None,
                    help="base URL to poll GET /chains/nightly-close/<date> (default: --rest, else "
                         "http://localhost:8080; '' to disable)")
    ap.add_argument("--refire-during-run", action="store_true")
    ap.add_argument("--refire-after-done", action="store_true")
    ap.add_argument("--drop-done-once", action="store_true")
    ap.add_argument("--timeout", type=float, default=900, help="seconds to wait for completion (default 900)")
    ap.add_argument("--ack-timeout", type=float, default=60, help="seconds to wait for a pickup (default 60)")
    ap.add_argument("--redrive-after", type=float, default=5, help="seconds after a lost DONE before re-sending READY")
    ap.add_argument("--settle", type=float, default=10,
                    help="seconds to keep watching after a re-fire, to catch a second run (default 10)")
    ap.add_argument("--poll", type=float, default=1.0)
    ap.add_argument("--json-report", default=None, help="also write the report as JSON")
    return ap.parse_args(argv)


def main(argv=None) -> int:
    return MainframeSide(parse_args(argv)).run()


if __name__ == "__main__":
    sys.exit(main())
