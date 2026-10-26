"""Run history: the CSV schema timegap.py reads, and converters that produce it.

Schema (header row required, one row per run):

    job_name    the job name as in the definitions
    folder      Control-M folder (top level), Autosys top-level box, or empty
    order_date  the business date the run belongs to, YYYY-MM-DD
    start_time  ISO 8601, e.g. 2026-10-27T01:02:11
    end_time    ISO 8601
    status      OK or NOTOK (anything else is kept but treated like NOTOK)
    host        agent / machine, may be empty

Converters:

    python -m ctminv history ctm  status1.json status2.json ... --out history.csv
        input: JSON printed by `ctm run jobs:status::get -s "..."` (one file per
        snapshot; collect a snapshot every morning, the active environment only
        holds recent order dates)

    python -m ctminv history autorep  report1.txt ... --out history.csv [--day-starts-at 12]
        input: text printed by `autorep -J <job> -s -r -<n>` or `autorep -J ALL -s`
        (summary reports). Autosys has no order date, so a run that starts before
        --day-starts-at (hour, default 12) belongs to the previous business date.
"""
from __future__ import annotations

import csv
import json
import re
from datetime import date, datetime, timedelta

FIELDS = ["job_name", "folder", "order_date", "start_time", "end_time", "status", "host"]

_CTM_TIME_FORMATS = ("%Y%m%d%H%M%S", "%b %d, %Y, %I:%M:%S %p", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S")


def parse_ctm_time(v: str):
    v = (v or "").strip().replace(" ", " ")
    if not v:
        return None
    for f in _CTM_TIME_FORMATS:
        try:
            return datetime.strptime(v, f)
        except ValueError:
            pass
    try:
        return datetime.fromisoformat(v)
    except ValueError:
        return None


def parse_order_date(v: str):
    v = (v or "").strip()
    if re.fullmatch(r"\d{6}", v):
        return datetime.strptime(v, "%y%m%d").date()
    if re.fullmatch(r"\d{8}", v):
        return datetime.strptime(v, "%Y%m%d").date()
    try:
        return date.fromisoformat(v)
    except ValueError:
        return None


def ctm_status_to_rows(doc: dict):
    """Rows from one `ctm run jobs:status::get` JSON document.

    Field names (jobId, name, folder, type, status, startTime, endTime, orderDate,
    host) are those of BMC's JobRunStatus model; the time format seen in BMC's own
    example output is "Oct 2, 2023, 11:42:59 AM", order dates are YYMMDD.
    """
    for s in doc.get("statuses", []) or []:
        if (s.get("type") or "").lower() in ("folder", "subfolder"):
            continue
        status = (s.get("status") or "").strip()
        if not status.lower().startswith("ended"):
            continue  # still waiting or running: no end time yet
        start, end = parse_ctm_time(s.get("startTime")), parse_ctm_time(s.get("endTime"))
        od = parse_order_date(s.get("orderDate"))
        if not (start and end and od):
            continue
        yield {
            "job_name": s.get("name", ""),
            "folder": (s.get("folder") or "").split("/")[0],
            "order_date": od.isoformat(),
            "start_time": start.isoformat(timespec="seconds"),
            "end_time": end.isoformat(timespec="seconds"),
            "status": "OK" if status.lower() in ("ended ok", "ended_ok") else "NOTOK",
            "host": s.get("host", ""),
            "_run": s.get("jobId", "") + "#" + str(s.get("numberOfRuns", "")),
        }


_AUTOREP_ROW = re.compile(
    r"^\s*(?P<name>\S+)\s+(?P<start>\d{2}/\d{2}/\d{4}\s+\d{2}:\d{2}:\d{2})\s+"
    r"(?P<end>\d{2}/\d{2}/\d{4}\s+\d{2}:\d{2}:\d{2})\s+(?P<st>[A-Z]{2})(?:/\w+)?\s+(?P<run>\d+)/(?P<ntry>\d+)")


def autorep_to_rows(text: str, day_starts_at: int = 12, box_of=None):
    """Rows from autorep summary reports (columns as in the Broadcom examples).

    Only finished runs (SU = success, FA = failure, TE = terminated) are kept.
    """
    box_of = box_of or {}
    for line in text.splitlines():
        m = _AUTOREP_ROW.match(line)
        if not m:
            continue
        st = m.group("st")
        if st not in ("SU", "FA", "TE"):
            continue
        start = datetime.strptime(m.group("start"), "%m/%d/%Y %H:%M:%S")
        end = datetime.strptime(m.group("end"), "%m/%d/%Y %H:%M:%S")
        od = start.date() - timedelta(days=1) if start.hour < day_starts_at else start.date()
        name = m.group("name")
        yield {
            "job_name": name, "folder": box_of.get(name, ""), "order_date": od.isoformat(),
            "start_time": start.isoformat(timespec="seconds"), "end_time": end.isoformat(timespec="seconds"),
            "status": "OK" if st == "SU" else "NOTOK", "host": "",
            "_run": f"{name}#{m.group('run')}/{m.group('ntry')}",
        }


def write_rows(rows, path) -> int:
    seen, n = set(), 0
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=FIELDS, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            k = r.get("_run") or tuple(r[f] for f in FIELDS)
            if k in seen:  # the same run appears in consecutive snapshots
                continue
            seen.add(k)
            w.writerow(r)
            n += 1
    return n


def load_ctm_files(paths):
    for p in paths:
        with open(p, "r", encoding="utf-8") as fh:
            yield from ctm_status_to_rows(json.load(fh))


def load_autorep_files(paths, day_starts_at=12, box_of=None):
    for p in paths:
        with open(p, "r", encoding="utf-8", errors="replace") as fh:
            yield from autorep_to_rows(fh.read(), day_starts_at, box_of)
