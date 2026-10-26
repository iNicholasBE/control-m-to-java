"""Command line entry point: python -m ctminv <command> ...

Commands:
  parse      FILES...                 list jobs (or --edges) found in definitions
  edges      FILES... --out E.csv     edges as CSV (src_folder, src_job, dst_folder, dst_job, kind, label),
                                      container -> member included; the input timegap.sql expects
  classify   FILES... [options]       four-bucket classification + CSV
  timegap    --history H --definitions FILES...   hidden-dependency candidates
  history    ctm|autorep FILES... --out H.csv     build the run-history CSV
  synth      [--jobs 9000] ...        synthetic estate with planted truth
  evaluate   --candidates C --planted P --definitions FILES...
"""
from __future__ import annotations

import argparse
import sys


def print_estate(estate, edges=False, out=sys.stdout):
    if edges:
        print("src\tdst\tkind\tlabel", file=out)
        for e in estate.edges:
            print(f"{e.src}\t{e.dst}\t{e.kind}\t{e.label}", file=out)
        return
    print("key\tfolder\ttype\thost\tapplication\tsub_application\tcontainer\tresources\twhen", file=out)
    for j in estate.jobs.values():
        res = ";".join(f"{n}:{t}:{q}" for n, t, q in j.resources)
        print(f"{j.key}\t{j.folder}\t{j.type}\t{j.host}\t{j.application}\t{j.sub_application}\t"
              f"{j.container}\t{res}\t{j.when}", file=out)
    print(f"# {len(estate.real_jobs())} jobs, {len(estate.jobs) - len(estate.real_jobs())} containers, "
          f"{len(estate.edges)} edges, {sum(len(v) for v in estate.dangling_in.values())} unresolved inbound",
          file=out)


def _parse(argv):
    from .model import load_definitions
    ap = argparse.ArgumentParser(prog="ctminv parse")
    ap.add_argument("files", nargs="+")
    ap.add_argument("--edges", action="store_true")
    a = ap.parse_args(argv)
    print_estate(load_definitions(a.files), edges=a.edges)
    return 0


def _edges(argv):
    import csv
    from .model import load_definitions
    ap = argparse.ArgumentParser(prog="ctminv edges")
    ap.add_argument("files", nargs="+")
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    est = load_definitions(a.files)
    rows = [(est.jobs[e.src], est.jobs[e.dst], e.kind, e.label) for e in est.edges]
    rows += [(est.jobs[j.container], j, "contains", "") for j in est.jobs.values()
             if j.container and j.container in est.jobs]
    with open(a.out, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["src_folder", "src_job", "dst_folder", "dst_job", "kind", "label"])
        for s, d, kind, label in rows:
            w.writerow([s.folder, s.name, d.folder, d.name, kind, label])
    print(f"wrote {len(rows)} edges to {a.out}")
    return 0


def _history(argv):
    from . import history
    ap = argparse.ArgumentParser(prog="ctminv history", description=history.__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("kind", choices=["ctm", "autorep"])
    ap.add_argument("files", nargs="+")
    ap.add_argument("--out", required=True)
    ap.add_argument("--day-starts-at", type=int, default=12)
    ap.add_argument("--definitions", nargs="*", default=[], help="JIL files, to fill the folder column with the top box")
    a = ap.parse_args(argv)
    if a.kind == "ctm":
        rows = history.load_ctm_files(a.files)
    else:
        box_of = {}
        if a.definitions:
            from .model import load_definitions
            box_of = {j.name: j.folder for j in load_definitions(a.definitions).jobs.values()}
        rows = history.load_autorep_files(a.files, a.day_starts_at, box_of)
    n = history.write_rows(rows, a.out)
    print(f"wrote {n} runs to {a.out}")
    return 0


def main(argv=None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    cmd, rest = argv[0], argv[1:]
    if cmd == "parse":
        return _parse(rest)
    if cmd == "edges":
        return _edges(rest)
    if cmd == "classify":
        from .classify import main as m
        return m(rest)
    if cmd == "timegap":
        from .timegap import main as m
        return m(rest)
    if cmd == "history":
        return _history(rest)
    if cmd == "synth":
        from .synth import main as m
        return m(rest)
    if cmd == "evaluate":
        from .evaluate_timegap import main as m
        return m(rest)
    print(f"unknown command {cmd!r}\n{__doc__}", file=sys.stderr)
    return 2
