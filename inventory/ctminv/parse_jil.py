"""Parse Autosys JIL (``jil`` input or ``autorep -J ALL -q`` output).

Reads ``insert_job`` / ``update_job`` / ``delete_job`` blocks and the
attributes ``job_type``, ``box_name``, ``machine``, ``command``,
``application``, ``group``, ``condition``, ``resources``, ``watch_file`` and
the date attributes. ``autorep -q`` prints ``insert_job: X   job_type: Y`` on
one line; that is handled. Comments ``/* ... */`` and ``#`` lines are skipped.

Conditions: ``s()``/``success()``, ``f()``/``failure()``, ``d()``/``done()``,
``t()``/``terminated()``, ``n()``/``notrunning()``, ``e()``/``exitcode()``
become edges from the named job. A job name with ``^INSTANCE`` (another
Autosys instance) and ``v()``/``value()`` global variables cannot be resolved
inside the estate and are recorded as external references.

Usage: python -m ctminv.parse_jil FILE.jil [--edges]
"""
from __future__ import annotations

import argparse
import re
import sys

from .model import Estate, Job

SUBCOMMANDS = ("insert_job", "update_job", "delete_job", "delete_box", "override_job",
               "insert_machine", "update_machine", "delete_machine", "insert_resource",
               "update_resource", "delete_resource", "insert_xinst", "insert_cycle",
               "insert_job_type", "insert_blob", "insert_glob", "delete_glob")
# attributes autorep -q may put on the same line as insert_job
_INLINE_SPLIT = re.compile(r"\s{2,}(?=job_type\s*:)|\s+(?=job_type\s*:)")
_ATTR = re.compile(r"^\s*([A-Za-z][A-Za-z0-9_]*)\s*:\s?(.*)$")
_COND = re.compile(
    r"\b(success|failure|done|terminated|notrunning|exitcode|s|f|d|t|n|e)\s*\(\s*([^,()\s]+)\s*(?:,[^)]*)?\)",
    re.I)
_VALUE = re.compile(r"\b(?:value|v)\s*\(\s*([^)\s]+)\s*\)", re.I)
_RES = re.compile(r"\(\s*([^,()\s]+)\s*,([^)]*)\)")
MAINFRAME_JOB_TYPES = {"ZOS", "ZOSM", "ZOSDST", "ZOSDT"}


def _strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("#"))


def _unquote(v: str) -> str:
    v = v.strip()
    if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
        return v[1:-1]
    return v


def parse_condition(text: str):
    """Return ([(job_name, status_function)], [external refs]) for a condition string."""
    refs, external = [], []
    for fn, name in _COND.findall(text):
        name = name.replace("\\:", ":")
        if "^" in name:
            external.append(f"{fn}({name})")
            continue
        refs.append((name, fn.lower()))
    for var in _VALUE.findall(text):
        external.append(f"value({var})")
    return refs, external


def parse_string(text: str, link: bool = True) -> Estate:
    estate = Estate()
    blocks: list[tuple[str, str, dict]] = []  # (subcommand, object name, attrs)
    current = None
    for raw in _strip_comments(text).splitlines():
        if not raw.strip():
            continue
        parts = _INLINE_SPLIT.split(raw.strip()) if raw.strip().startswith(("insert_job", "update_job")) else [raw]
        for line in parts:
            m = _ATTR.match(line)
            if not m:
                continue
            key, value = m.group(1).lower(), _unquote(m.group(2))
            if key in SUBCOMMANDS:
                current = (key, value.split()[0] if value else "", {})
                blocks.append(current)
            elif current is not None:
                # repeated attributes (rare) keep the last value, as jil does
                current[2][key] = value

    raw_jobs: dict[str, dict] = {}
    for sub, name, attrs in blocks:
        if sub == "insert_job":
            raw_jobs[name] = dict(attrs)
        elif sub == "update_job" and name in raw_jobs:
            raw_jobs[name].update(attrs)
        elif sub == "update_job":
            raw_jobs[name] = dict(attrs)
        elif sub in ("delete_job", "delete_box"):
            raw_jobs.pop(name, None)

    def top_box(name, seen=None):
        seen = seen or set()
        box = raw_jobs.get(name, {}).get("box_name", "")
        if not box or box in seen or box not in raw_jobs:
            return box
        seen.add(box)
        parent = top_box(box, seen)
        return parent or box

    for name, a in raw_jobs.items():
        jtype = a.get("job_type", "CMD").upper()
        is_box = jtype in ("BOX", "B")
        top = top_box(name) or (name if is_box else "")
        job = Job(
            key=name,
            name=name,
            folder=top,
            type=jtype,
            host=a.get("machine", ""),
            application=a.get("application", ""),
            sub_application=a.get("group", ""),
            platform="zos" if jtype in MAINFRAME_JOB_TYPES else "",
            source="jil",
            container=a.get("box_name", ""),
            is_container=is_box,
            when={k: a[k] for k in ("date_conditions", "days_of_week", "run_calendar", "exclude_calendar",
                                    "start_times", "start_mins", "run_window", "must_complete_times",
                                    "watch_file", "n_retrys") if k in a},
        )
        if "condition" in a:
            refs, external = parse_condition(a["condition"])
            job.cond_refs = [(n, fn) for n, fn in refs]
            job.external_refs = external
        if "resources" in a:
            for res, opts in _RES.findall(a["resources"]):
                q = re.search(r"quantity\s*=\s*(\w+)", opts, re.I)
                job.resources.append((res, "virtual", q.group(1) if q else ""))
        estate.add_job(job)
    if link:
        estate.link()
    return estate


def parse_file(path: str, link: bool = True) -> Estate:
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return parse_string(fh.read(), link)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="+")
    ap.add_argument("--edges", action="store_true")
    args = ap.parse_args(argv)
    from .cli import print_estate
    from .model import load_definitions
    print_estate(load_definitions(args.files), edges=args.edges)
    return 0


if __name__ == "__main__":
    sys.exit(main())
