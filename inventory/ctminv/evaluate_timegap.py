"""Score timegap candidates against the planted truth of a synthetic estate.

Prints precision and recall at two levels:

  pairs    a candidate (A, B) is correct only if it is exactly a planted pair
  groups   candidates grouped by (flow of A, B), the file a reviewer reads. A group is
           correct if a planted pair (A', B') has A' in that flow and B is B' or runs
           downstream of B'. Timegap cannot tell which partition of a fan-out B really
           waits for, but it can tell which flow.

Recall is given against all planted pairs, and against the planted pairs whose
realised hit rate reached the confidence threshold on at least --min-nights shared
nights (the ones the rule can see at all).

Usage:
    python -m ctminv evaluate --candidates C.csv --planted planted.csv --definitions definitions.json
"""
from __future__ import annotations

import argparse
import csv
import sys
from collections import defaultdict, deque

from .model import load_definitions


def _closure(estate):
    succ = defaultdict(set)
    for e in estate.edges:
        succ[e.src].add(e.dst)
    cache = {}

    def down(a):
        if a not in cache:
            seen, q = {a}, deque([a])
            while q:
                x = q.popleft()
                for y in succ.get(x, ()):
                    if y not in seen:
                        seen.add(y)
                        q.append(y)
            cache[a] = seen
        return cache[a]
    return down


def evaluate(candidates, planted, estate, min_conf=0.9, min_nights=10):
    from .timegap import flows
    cand = {(r["a_job"], r["b_job"]) for r in candidates}
    truth = {(r["a_job"], r["b_job"]) for r in planted}
    visible = {(r["a_job"], r["b_job"]) for r in planted
               if r.get("realised_rate") not in ("", None) and float(r["realised_rate"]) >= min_conf
               and int(r.get("nights_both_ran") or 0) >= min_nights}
    down = _closure(estate) if estate is not None else (lambda a: {a})
    flow_of = flows(estate) if estate is not None else (lambda k: k)
    strict_tp = cand & truth

    # group level: one row per (flow of A, B)
    groups = {(r.get("a_flow") or flow_of(r["a_job"]), r["b_job"]) for r in candidates}
    explained_groups = set()
    for pa, pb in truth:
        for b in down(pb):
            explained_groups.add((flow_of(pa), b))
    good_groups = groups & explained_groups
    found_groups_all = {p for p in truth if (flow_of(p[0]), p[1]) in groups}
    found_groups_visible = {p for p in visible if (flow_of(p[0]), p[1]) in groups}

    def ratio(x, y):
        return x / y if y else float("nan")
    return {
        "candidate_pairs": len(cand),
        "planted": len(truth),
        "planted_visible (rate and nights ok)": len(visible),
        "pairs: true positives": len(strict_tp),
        "pairs: precision": ratio(len(strict_tp), len(cand)),
        "pairs: recall, all planted": ratio(len(strict_tp), len(truth)),
        "pairs: recall, visible planted": ratio(len(strict_tp & visible), len(visible)),
        "groups (flow of A, B)": len(groups),
        "groups: explained by a planted pair": len(good_groups),
        "groups: precision": ratio(len(good_groups), len(groups)),
        "groups: recall, all planted": ratio(len(found_groups_all), len(truth)),
        "groups: recall, visible planted": ratio(len(found_groups_visible), len(visible)),
    }


def read_csv(path):
    with open(path, newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--candidates", required=True)
    ap.add_argument("--planted", required=True)
    ap.add_argument("--definitions", nargs="*", default=[])
    ap.add_argument("--min-confidence", type=float, default=0.9)
    ap.add_argument("--min-nights", type=int, default=10)
    args = ap.parse_args(argv)
    estate = load_definitions(args.definitions) if args.definitions else None
    res = evaluate(read_csv(args.candidates), read_csv(args.planted), estate, args.min_confidence, args.min_nights)
    for k, v in res.items():
        print(f"{k:<42} {v:.3f}" if isinstance(v, float) else f"{k:<42} {v}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
