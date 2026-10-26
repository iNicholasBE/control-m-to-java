"""Put every job of an estate into one of four migration buckets.

    single          no conditions in or out, not in a box or a chain
    in_domain       every edge stays inside the same domain (folder, application, or a
                    name pattern), on the same host and platform
    cross_system    an edge crosses the domain, the host (unless --ignore-host), or the
                    platform (mainframe <-> distributed), or the job waits for something
                    nobody in the analysed definitions produces
    mainframe_only  the job runs on z/OS (job type, platform or host) and every edge it
                    has stays on the mainframe

A job inside a SubFolder (Control-M) or BOX (Autosys) inherits the edges of
its containers, because it cannot start before they do. Containers themselves
are not classified.

Usage:
    python -m ctminv classify FILES... [--domain folder|application|regex:PATTERN]
                              [--ignore-host] [--mainframe-host REGEX] [--out buckets.csv]
"""
from __future__ import annotations

import argparse
import csv
import re
import sys
from collections import Counter

from .model import Estate, load_definitions

BUCKETS = ("single", "in_domain", "cross_system", "mainframe_only")
DEFAULT_MF_HOST = r"(?i)(^|[^a-z])(zos|z/os|mvs|mainframe|lpar|sysplex)([^a-z]|$)"


class Classifier:
    def __init__(self, estate: Estate, domain: str = "folder", ignore_host: bool = False,
                 mainframe_host: str = DEFAULT_MF_HOST):
        self.estate = estate
        self.ignore_host = ignore_host
        self.mf_host = re.compile(mainframe_host) if mainframe_host else None
        if domain.startswith("regex:"):
            pat = re.compile(domain[len("regex:"):])

            def key(job):
                m = pat.search(job.name)
                if not m:
                    return f"(no match: {job.name})"
                return m.group(1) if m.groups() else m.group(0)
            self.domain_of = key
        elif domain == "application":
            self.domain_of = lambda j: j.application or f"(no application: {j.folder})"
        elif domain == "folder":
            self.domain_of = lambda j: j.folder or "(no folder)"
        else:
            raise ValueError(f"unknown domain key {domain!r}: use folder, application or regex:PATTERN")
        self.out_edges: dict[str, list] = {}
        self.in_edges: dict[str, list] = {}
        for e in estate.edges:
            self.out_edges.setdefault(e.src, []).append(e)
            self.in_edges.setdefault(e.dst, []).append(e)

    def is_mainframe(self, job) -> bool:
        if job.platform == "zos":
            return True
        if job.type.lower().startswith("job:zos"):
            return True
        return bool(self.mf_host and job.host and self.mf_host.search(job.host))

    def _ancestors(self, job):
        seen = set()
        c = job.container
        while c and c in self.estate.jobs and c not in seen:
            seen.add(c)
            yield self.estate.jobs[c]
            c = self.estate.jobs[c].container

    def _partner(self, key):
        """An edge to a container counts as an edge to whatever runs in it: use the container itself."""
        return self.estate.jobs.get(key)

    def classify_job(self, job):
        nodes = [job] + list(self._ancestors(job))
        node_keys = {n.key for n in nodes}
        partners = []
        for n in nodes:
            for e in self.out_edges.get(n.key, []):
                if e.dst not in node_keys:
                    partners.append(("out", e, self._partner(e.dst)))
            for e in self.in_edges.get(n.key, []):
                if e.src not in node_keys:
                    partners.append(("in", e, self._partner(e.src)))
        dangling = [d for n in nodes for d in self.estate.dangling_in.get(n.key, [])]
        external = [x for n in nodes for x in n.external_refs]
        in_container = bool(job.container)
        me_mf = self.is_mainframe(job)

        # 1. platform crossing
        for direction, e, other in partners:
            if other is not None and self.is_mainframe(other) != me_mf:
                side = "mainframe" if self.is_mainframe(other) else "distributed"
                return "cross_system", f"edge {direction} to {side} job {other.key} ({e.label})"
        # 2. something outside the analysed definitions
        if dangling:
            return "cross_system", f"waits for {dangling[0]} that no analysed job produces"
        if external:
            return "cross_system", f"depends on external reference {external[0]}"
        # 3. domain / host crossing
        my_domain = self.domain_of(job)
        for direction, e, other in partners:
            if other is None:
                continue
            if self.domain_of(other) != my_domain:
                return "cross_system", (f"edge {direction} to {other.key} in domain "
                                        f"{self.domain_of(other)!r} (mine: {my_domain!r})")
            if not self.ignore_host and other.host and job.host and other.host != job.host \
                    and not other.is_container:
                return "cross_system", f"edge {direction} to {other.key} on host {other.host} (mine: {job.host})"
        if me_mf:
            return "mainframe_only", "z/OS job, all edges stay on the mainframe" if partners else "z/OS job, no edges"
        if not partners and not in_container:
            return "single", "no conditions in or out"
        if not partners:
            return "in_domain", f"no edges of its own, inside container {job.container}"
        return "in_domain", f"{len(partners)} edge(s), all inside domain {my_domain!r}"

    def run(self):
        rows = []
        for job in self.estate.real_jobs():
            bucket, reason = self.classify_job(job)
            rows.append({
                "job_key": job.key, "job_name": job.name, "folder": job.folder,
                "application": job.application, "host": job.host, "type": job.type,
                "domain": self.domain_of(job), "bucket": bucket, "reason": reason,
            })
        return rows


def distribution(rows) -> Counter:
    c = Counter(r["bucket"] for r in rows)
    return Counter({b: c.get(b, 0) for b in BUCKETS})


def format_distribution(rows) -> str:
    dist = distribution(rows)
    total = sum(dist.values()) or 1
    lines = [f"{'bucket':<15} {'jobs':>7} {'share':>7}"]
    for b in BUCKETS:
        lines.append(f"{b:<15} {dist[b]:>7} {100.0 * dist[b] / total:>6.1f}%")
    lines.append(f"{'total':<15} {sum(dist.values()):>7}")
    return "\n".join(lines)


def write_csv(rows, path) -> None:
    fields = ["job_key", "job_name", "folder", "application", "host", "type", "domain", "bucket", "reason"]
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="+", help="definition files (.json, .xml, .jil)")
    ap.add_argument("--domain", default="folder", help="folder (default), application, or regex:PATTERN on the job name")
    ap.add_argument("--ignore-host", action="store_true", help="do not count a host change as crossing systems")
    ap.add_argument("--mainframe-host", default=DEFAULT_MF_HOST, help="regex on host/agent names that run on z/OS")
    ap.add_argument("--out", default="buckets.csv", help="CSV with one row per job (default buckets.csv)")
    args = ap.parse_args(argv)
    estate = load_definitions(args.files)
    rows = Classifier(estate, args.domain, args.ignore_host, args.mainframe_host).run()
    write_csv(rows, args.out)
    print(format_distribution(rows))
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
