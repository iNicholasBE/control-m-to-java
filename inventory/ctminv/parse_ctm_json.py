"""Parse Control-M Automation API JSON (what ``ctm deploy jobs::get`` returns).

Handles: ``Defaults``, ``Folder`` / ``SimpleFolder`` / ``SubFolder`` nesting,
``Job:*`` objects, ``Flow`` objects with a ``Sequence``, events in
``WaitForEvents`` / ``AddEvents`` / ``DeleteEvents`` blocks (recognised by
their ``Type``, whatever the key is called), conditional ``Event:Add`` actions
inside ``If`` blocks, ``Resource:*`` objects and ``When``.

Usage: python -m ctminv.parse_ctm_json FILE.json [--edges]
"""
from __future__ import annotations

import argparse
import json
import re
import sys

from .model import Edge, Estate, Job

FOLDER_TYPES = {"Folder", "SimpleFolder"}
SUBFOLDER_TYPES = {"SubFolder"}
INHERITED = ("Host", "Application", "SubApplication", "RunAs", "ControlmServer")
MAINFRAME_TYPE = re.compile(r"^Job:zOS", re.I)


def _events(block) -> list[str]:
    out = []
    for item in (block or {}).get("Events", []) or []:
        if isinstance(item, dict) and "Event" in item:
            out.append(str(item["Event"]))
        # strings such as "(", ")", "OR", "AND" are boolean glue: the edge exists either way
    return out


def _walk_conditional_adds(obj, acc):
    """Event:Add actions nested in If blocks still create an outgoing edge."""
    if isinstance(obj, dict):
        if obj.get("Type") == "Event:Add" and "Event" in obj:
            acc.append(str(obj["Event"]))
        for v in obj.values():
            if isinstance(v, (dict, list)):
                _walk_conditional_adds(v, acc)
    elif isinstance(obj, list):
        for v in obj:
            _walk_conditional_adds(v, acc)


def _collect_common(obj: dict, job: Job) -> None:
    for k, v in obj.items():
        if not isinstance(v, dict):
            continue
        t = v.get("Type", "")
        if t == "WaitForEvents":
            job.in_events += [e for e in _events(v) if e not in job.in_events]
        elif t == "AddEvents":
            job.out_events += [e for e in _events(v) if e not in job.out_events]
        elif t == "DeleteEvents":
            job.del_events += [e for e in _events(v) if e not in job.del_events]
        elif t.startswith("Resource:"):
            amount = v.get("Quantity") or v.get("LockType") or v.get("MutexType") or ""
            job.resources.append((k, t, str(amount)))
        elif t == "If" or t.startswith("If:"):
            acc: list[str] = []
            _walk_conditional_adds(v, acc)
            job.out_events += [e for e in acc if e not in job.out_events]


def _merge(defaults: dict, obj: dict) -> dict:
    merged = {k: v for k, v in defaults.items() if not isinstance(v, dict) or k == "When"}
    merged.update(obj)
    return merged


class _Parser:
    def __init__(self, source_name: str):
        self.estate = Estate()
        self.source_name = source_name
        self.defaults: dict = {}
        self.flows: list[tuple[str, list[str], str]] = []  # (container key prefix, sequence, top folder)

    def parse(self, doc: dict) -> Estate:
        self.defaults = doc.get("Defaults", {}) if isinstance(doc.get("Defaults"), dict) else {}
        for name, obj in doc.items():
            if name == "Defaults" or not isinstance(obj, dict):
                continue
            t = obj.get("Type", "")
            if t in FOLDER_TYPES:
                fobj = _merge({k: v for k, v in self.defaults.items() if k not in ("Job", "Folder")}, obj)
                fobj = _merge(self.defaults.get("Folder", {}), fobj) if isinstance(self.defaults.get("Folder"), dict) else fobj
                self._folder(name, fobj, top=name, prefix=name, inherited={})
            elif t.startswith("Job:"):
                # a job outside any folder (rare, but jobs::get on a single job can return it)
                self._job(name, obj, top="", prefix="", inherited={}, container="")
        self._resolve_flows()
        return self.estate

    def _folder(self, name, obj, top, prefix, inherited, container=""):
        inh = dict(inherited)
        for k in INHERITED:
            if k in obj:
                inh[k] = obj[k]
        if obj.get("Type") in SUBFOLDER_TYPES:
            node = Job(key=prefix, name=name, folder=top, type=obj["Type"], source="ctm-json",
                       container=container, is_container=True,
                       application=str(obj.get("Application", inh.get("Application", ""))),
                       sub_application=str(obj.get("SubApplication", inh.get("SubApplication", ""))),
                       host=str(inh.get("Host", "")), when=obj.get("When", {}) or {})
            _collect_common(obj, node)
            self.estate.add_job(node)
            container = prefix
        for child_name, child in obj.items():
            if not isinstance(child, dict):
                continue
            t = child.get("Type", "")
            if t in SUBFOLDER_TYPES:
                self._folder(child_name, child, top, f"{prefix}/{child_name}", inh, container)
            elif t.startswith("Job:"):
                self._job(child_name, child, top, prefix, inh, container)
            elif t == "Flow":
                self.flows.append((prefix, [str(s) for s in child.get("Sequence", [])], top))

    def _job(self, name, obj, top, prefix, inherited, container):
        job_defaults = self.defaults.get("Job", {}) if isinstance(self.defaults.get("Job"), dict) else {}
        top_defaults = {k: v for k, v in self.defaults.items() if not isinstance(v, dict)}
        merged = dict(top_defaults)
        merged.update(inherited)
        merged.update({k: v for k, v in job_defaults.items() if not isinstance(v, dict) or k == "When"})
        merged.update(obj)
        jtype = str(merged.get("Type", ""))
        host = str(merged.get("Host", ""))
        job = Job(
            key=f"{prefix}/{name}" if prefix else name,
            name=name,
            folder=top,
            type=jtype,
            host=host,
            application=str(merged.get("Application", "")),
            sub_application=str(merged.get("SubApplication", "")),
            platform="zos" if MAINFRAME_TYPE.match(jtype) else "",
            source="ctm-json",
            container=container,
            when=merged.get("When", {}) or {},
        )
        _collect_common(obj, job)
        self.estate.add_job(job)

    def _resolve_flows(self):
        for prefix, seq, top in self.flows:
            keys = []
            for item in seq:
                # Sequence items are job names relative to the container, possibly "Sub/Job"
                candidates = [f"{prefix}/{item}", f"{top}/{item}", item]
                key = next((c for c in candidates if c in self.estate.jobs), None)
                keys.append(key)
            for a, b in zip(keys, keys[1:]):
                if a and b:
                    self.estate.edges.append(Edge(a, b, "flow", "Flow"))


def parse_doc(doc: dict, source_name: str = "", link: bool = True) -> Estate:
    estate = _Parser(source_name).parse(doc)
    if link:
        estate.link()
    return estate


def parse_file(path: str, link: bool = True) -> Estate:
    with open(path, "r", encoding="utf-8") as fh:
        doc = json.load(fh)
    return parse_doc(doc, path, link)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="+")
    ap.add_argument("--edges", action="store_true", help="print edges instead of jobs")
    args = ap.parse_args(argv)
    from .cli import print_estate
    from .model import load_definitions
    print_estate(load_definitions(args.files), edges=args.edges)
    return 0


if __name__ == "__main__":
    sys.exit(main())
