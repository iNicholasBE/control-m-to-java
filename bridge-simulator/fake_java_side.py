#!/usr/bin/env python3
"""A stand-in for the Java side, so mainframe_side.py can be tried without building Java.

It implements the same bridge contract as the two Java implementations:
READY files in the inbox (moved to accepted/ when picked up), DONE in the
outbox, POST /chains/nightly-close?businessDate=... and
GET /chains/nightly-close/<businessDate>.

  --mode deterministic   the run id is derived from the business date, so a second
                         trigger for the same key gets the existing run back
  --mode naive           every trigger starts a new run with a random id (what you get
                         with a plain "enqueue on message" and no idempotency key)
  --mode deaf            never picks anything up (the "Java never acknowledges" case)

Standard library only:
  python3 fake_java_side.py --inbox ./bridge/inbox --outbox ./bridge/outbox --mode naive
"""
from __future__ import annotations

import argparse
import json
import re
import threading
import time
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

READY = re.compile(r"NIGHTLY-CLOSE-(\d{4}-\d{2}-\d{2})-READY$")
NAMESPACE = uuid.UUID("6f1c1a4e-2b8e-4c55-9a51-6d1f0e0c7a11")


class Runs:
    def __init__(self, mode, duration, outbox):
        self.mode, self.duration, self.outbox = mode, duration, Path(outbox)
        self.lock = threading.Lock()
        self.by_date: dict[str, list[dict]] = {}

    def submit(self, business_date: str, via: str):
        with self.lock:
            runs = self.by_date.setdefault(business_date, [])
            if self.mode == "deterministic":
                run_id = str(uuid.uuid5(NAMESPACE, f"nightly-close/{business_date}/1"))
                if runs:
                    print(f"{via}: {business_date} -> EXISTING {run_id}", flush=True)
                    return 200, {"outcome": "EXISTING", **runs[0]}
            else:
                run_id = str(uuid.uuid4())
            run = {"runId": run_id, "businessDate": business_date, "state": "RUNNING",
                   "startedAt": datetime.now(timezone.utc).isoformat(timespec="milliseconds")}
            runs.append(run)
            print(f"{via}: {business_date} -> CREATED {run_id}", flush=True)
            threading.Timer(self.duration, self._finish, args=(run,)).start()
            return 202, {"outcome": "CREATED", **run}

    def _finish(self, run):
        with self.lock:
            run["state"] = "DONE"
            run["finishedAt"] = datetime.now(timezone.utc).isoformat(timespec="milliseconds")
        self.outbox.mkdir(parents=True, exist_ok=True)
        (self.outbox / f"NIGHTLY-CLOSE-{run['businessDate']}-DONE").write_text(run["runId"] + "\n")
        print(f"run {run['runId']} DONE", flush=True)

    def status(self, business_date):
        with self.lock:
            runs = self.by_date.get(business_date)
            return dict(runs[-1]) if runs else None


def watch_inbox(runs: Runs, inbox: Path, mode: str, interval: float):
    accepted = inbox / "accepted"
    while True:
        if mode != "deaf" and inbox.is_dir():
            for f in sorted(inbox.iterdir()):
                m = READY.match(f.name)
                if not m:
                    continue
                runs.submit(m.group(1), "file")
                accepted.mkdir(exist_ok=True)
                f.replace(accepted / f"{f.name}.{time.time_ns()}")
        time.sleep(interval)


def make_handler(runs: Runs, mode: str):
    class Handler(BaseHTTPRequestHandler):
        def _send(self, code, body):
            data = json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_POST(self):
            u = urlparse(self.path)
            if mode == "deaf":
                return self._send(503, {"error": "not listening"})
            if u.path != "/chains/nightly-close":
                return self._send(404, {})
            bd = parse_qs(u.query).get("businessDate", [""])[0]
            if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", bd):
                return self._send(400, {"outcome": "REJECTED", "reason": "businessDate"})
            code, body = runs.submit(bd, "rest")
            self._send(code, body)

        def do_GET(self):
            m = re.fullmatch(r"/chains/nightly-close/(\d{4}-\d{2}-\d{2})", urlparse(self.path).path)
            s = runs.status(m.group(1)) if m else None
            self._send(200, s) if s else self._send(404, {})

        def log_message(self, *args):
            pass
    return Handler


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--inbox", default="./bridge/inbox")
    ap.add_argument("--outbox", default="./bridge/outbox")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--mode", choices=["deterministic", "naive", "deaf"], default="deterministic")
    ap.add_argument("--duration", type=float, default=8.0, help="seconds a chain run takes (default 8)")
    ap.add_argument("--scan-interval", type=float, default=0.5)
    a = ap.parse_args(argv)
    inbox = Path(a.inbox)
    inbox.mkdir(parents=True, exist_ok=True)
    runs = Runs(a.mode, a.duration, a.outbox)
    threading.Thread(target=watch_inbox, args=(runs, inbox, a.mode, a.scan_interval), daemon=True).start()
    server = ThreadingHTTPServer(("127.0.0.1", a.port), make_handler(runs, a.mode))
    print(f"fake Java side ({a.mode}) on http://127.0.0.1:{a.port}, inbox {inbox}, outbox {a.outbox}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
