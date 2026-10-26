"""The common model every parser produces: jobs, containers and edges."""
from __future__ import annotations

import os
from dataclasses import dataclass, field


@dataclass
class Job:
    """One job definition (or a container such as a SubFolder or a BOX).

    ``key`` is unique inside an estate. For Control-M it is
    ``folder/subfolder/name``; for Autosys it is the job name, which JIL
    already requires to be unique.
    """

    key: str
    name: str
    folder: str                      # top-level folder (Control-M) or top-level box (Autosys)
    type: str = ""
    host: str = ""
    application: str = ""
    sub_application: str = ""
    platform: str = ""               # "zos" when the definition says mainframe, else ""
    source: str = ""                 # ctm-json | ctm-xml | jil
    container: str = ""              # key of the enclosing SubFolder / box, "" if none
    is_container: bool = False       # SubFolder, SUB_FOLDER, or Autosys BOX
    when: dict = field(default_factory=dict)
    resources: list = field(default_factory=list)   # [(name, kind, amount)]
    in_events: list = field(default_factory=list)   # events this job waits for
    out_events: list = field(default_factory=list)  # events this job adds
    del_events: list = field(default_factory=list)  # events this job deletes
    cond_refs: list = field(default_factory=list)   # Autosys: [(upstream job name, condition text)]
    external_refs: list = field(default_factory=list)  # e.g. Autosys value(GLOBAL), job^OTHER_INSTANCE


@dataclass(frozen=True)
class Edge:
    src: str        # job key
    dst: str        # job key
    kind: str       # event | flow | condition
    label: str = "" # event name or condition text


@dataclass
class Estate:
    jobs: dict = field(default_factory=dict)        # key -> Job
    edges: list = field(default_factory=list)       # [Edge]
    dangling_in: dict = field(default_factory=dict)  # job key -> [event names nobody in the estate adds]

    def add_job(self, job: Job) -> None:
        if job.key in self.jobs:
            # Same key twice (for example the same folder exported twice): keep the first, merge events.
            old = self.jobs[job.key]
            old.in_events += [e for e in job.in_events if e not in old.in_events]
            old.out_events += [e for e in job.out_events if e not in old.out_events]
            return
        self.jobs[job.key] = job

    def merge(self, other: "Estate") -> "Estate":
        for j in other.jobs.values():
            self.add_job(j)
        self.edges.extend(other.edges)
        return self

    def real_jobs(self):
        return [j for j in self.jobs.values() if not j.is_container]

    def link(self) -> None:
        """Resolve references into edges.

        Control-M: every job that adds event E -> every job that waits for E.
        Autosys: the job named in s(), f(), d(), t(), n(), e() -> the job with the condition.
        A reference nobody in the estate satisfies is recorded in ``dangling_in``.
        """
        adders: dict[str, list[str]] = {}
        for j in self.jobs.values():
            for e in j.out_events:
                adders.setdefault(e, []).append(j.key)
        seen = set(self.edges)
        self.dangling_in = {}
        for j in self.jobs.values():
            for e in j.in_events:
                srcs = adders.get(e)
                if not srcs:
                    self.dangling_in.setdefault(j.key, []).append(e)
                    continue
                for s in srcs:
                    if s == j.key:
                        continue
                    edge = Edge(s, j.key, "event", e)
                    if edge not in seen:
                        seen.add(edge)
                        self.edges.append(edge)
        for j in self.jobs.values():
            for upstream, text in j.cond_refs:
                if upstream in self.jobs and upstream != j.key:
                    edge = Edge(upstream, j.key, "condition", text)
                    if edge not in seen:
                        seen.add(edge)
                        self.edges.append(edge)
                elif upstream not in self.jobs:
                    self.dangling_in.setdefault(j.key, []).append(f"job {upstream}")


def load_definitions(paths) -> Estate:
    """Load one or more definition files of any supported format into one estate."""
    from . import parse_ctm_json, parse_ctm_xml, parse_jil

    estate = Estate()
    for p in paths:
        fmt = detect_format(p)
        if fmt == "ctm-json":
            part = parse_ctm_json.parse_file(p, link=False)
        elif fmt == "ctm-xml":
            part = parse_ctm_xml.parse_file(p, link=False)
        else:
            part = parse_jil.parse_file(p, link=False)
        estate.merge(part)
    estate.link()
    return estate


def detect_format(path: str) -> str:
    ext = os.path.splitext(path)[1].lower()
    if ext == ".json":
        return "ctm-json"
    if ext == ".xml":
        return "ctm-xml"
    if ext in (".jil", ".txt"):
        return "jil"
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        head = fh.read(2048).lstrip()
    if head.startswith("{"):
        return "ctm-json"
    if head.startswith("<"):
        return "ctm-xml"
    return "jil"
