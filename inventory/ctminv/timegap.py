"""Find hidden dependencies: B starts right after A ends, night after night,
but no edge in the definitions links A to B.

For every pair (A, B) of jobs that ran on the same night (same order_date):

    hit on a night   <=>  0 <= start(B) - end(A) <= --window minutes (default 15)
    confidence       =    hits / nights both ran
    candidate        <=>  confidence >= --min-confidence (default 0.90)
                          and nights both ran >= --min-nights (default 10)
                          and the definitions do not link A to B

Optional (--min-tracking R): keep only candidates where B's start time moves with
A's end time across the hit nights: the 99.9% lower bound (Fisher z) of their
Pearson correlation must be >= R. Two jobs on fixed clock times with stable
runtimes can sit 5 minutes apart every night without depending on each other;
this filter removes most of those. The bound, not the raw correlation, because
with a million pairs tested a correlation of 0.85 over 13 nights happens by chance.

Every candidate also gets its flow: the connected part of the defined graph A
belongs to. If B waits for one partition of a 500-way fan-out, all 500 partitions
end at about the same time and all 500 pairs qualify; grouped by flow that is one
row to review. The grouped file (<out>-groups.csv) is the one to hand to people.

"Link" means a directed path A -> ... -> B through events, conditions, flows
and containers (default), or only a direct edge with --direct-only. Per job and
night, start = first start, end = last end of that night's runs, so a rerun
counts as one long run.

The search is exact but avoids comparing all pairs: a pair can only qualify if
it hits on one of its first floor(0.1 * nights-both-ran) + 1 shared nights, so
pairs are only recorded when that is still possible, and pairs whose misses
already exceed the allowance are dropped as the scan goes.

Usage:
    python -m ctminv timegap --history history.csv --definitions FILES... [--out candidates.csv]
"""
from __future__ import annotations

import argparse
import csv
import math
import statistics
import sys
import time
from bisect import bisect_left
from collections import defaultdict, deque
from datetime import datetime

from .model import Estate, load_definitions


def load_history(path, estate: Estate | None = None):
    """Return (job_keys, nights, runs) where runs[night_idx] = {job_idx: (start, end)} in epoch seconds."""
    by_folder_name, by_name = {}, defaultdict(list)
    if estate is not None:
        for j in estate.jobs.values():
            by_folder_name[(j.folder, j.name)] = j.key
            by_name[j.name].append(j.key)

    def key_for(folder, name):
        k = by_folder_name.get((folder, name))
        if k:
            return k
        if len(by_name.get(name, [])) == 1:
            return by_name[name][0]
        return f"{folder}/{name}" if folder else name

    agg: dict[tuple[str, str], list[float]] = {}
    with open(path, newline="", encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            try:
                s = datetime.fromisoformat(r["start_time"]).timestamp()
                e = datetime.fromisoformat(r["end_time"]).timestamp()
            except (ValueError, KeyError):
                continue
            k = (key_for(r.get("folder", ""), r["job_name"]), r["order_date"])
            cur = agg.get(k)
            if cur is None:
                agg[k] = [s, e]
            else:
                if s < cur[0]:
                    cur[0] = s
                if e > cur[1]:
                    cur[1] = e
    job_keys = sorted({k for k, _ in agg})
    nights = sorted({n for _, n in agg})
    jidx = {k: i for i, k in enumerate(job_keys)}
    nidx = {n: i for i, n in enumerate(nights)}
    runs = [dict() for _ in nights]
    for (k, n), (s, e) in agg.items():
        runs[nidx[n]][jidx[k]] = (s, e)
    return job_keys, nights, runs


def _reach_fn(estate: Estate, direct_only: bool):
    succ = defaultdict(set)
    for e in estate.edges:
        succ[e.src].add(e.dst)
    if not direct_only:
        for j in estate.jobs.values():
            if j.container:
                succ[j.container].add(j.key)  # a container starts before its members
    if direct_only:
        return lambda a, b: b in succ.get(a, ())
    cache: dict[str, set] = {}

    def reachable(a, b):
        if a not in cache:
            seen, q = set(), deque([a])
            while q:
                x = q.popleft()
                for y in succ.get(x, ()):
                    if y not in seen:
                        seen.add(y)
                        q.append(y)
            cache[a] = seen
        return b in cache[a]
    return reachable


def find_candidates(job_keys, nights, runs, estate: Estate | None = None, window_min=15.0,
                    min_conf=0.9, min_nights=10, direct_only=False, prune_every=3, verbose=False,
                    min_correlation=None):
    W = window_min * 60.0
    N = len(job_keys)
    T = len(nights)
    masks = [0] * N
    for t, night in enumerate(runs):
        bit = 1 << t
        for j in night:
            masks[j] |= bit
    miss_frac = 1.0 - min_conf

    def allowed(co_final):
        return math.floor(miss_frac * co_final + 1e-9)

    hits: dict[int, int] = {}
    t0 = time.time()
    for t, night in enumerate(runs):
        prefix = (1 << t) - 1
        order = sorted(night.items(), key=lambda kv: kv[1][0])
        starts = [se[0] for _, se in order]
        ids = [j for j, _ in order]
        for a, (sa, ea) in night.items():
            lo = bisect_left(starts, ea)
            hi_t = ea + W
            ma = masks[a]
            base = a * N
            i = lo
            while i < len(starts) and starts[i] <= hi_t:
                b = ids[i]
                i += 1
                if b == a:
                    continue
                pid = base + b
                h = hits.get(pid)
                if h is not None:
                    hits[pid] = h + 1
                    continue
                both = ma & masks[b]
                if (both & prefix).bit_count() <= allowed(both.bit_count()):
                    hits[pid] = 1
        if prune_every and (t + 1) % prune_every == 0:
            through = (1 << (t + 1)) - 1
            dead = []
            for pid, h in hits.items():
                both = masks[pid // N] & masks[pid % N]
                if (both & through).bit_count() - h > allowed(both.bit_count()):
                    dead.append(pid)
            for pid in dead:
                del hits[pid]
        if verbose:
            print(f"  night {t + 1}/{T}: {len(hits)} live pairs, {time.time() - t0:.1f}s", file=sys.stderr)

    linked = _reach_fn(estate, direct_only) if estate is not None else (lambda a, b: False)
    flow_of = flows(estate) if estate is not None else (lambda k: k)
    night_base = []
    for n in nights:
        try:
            night_base.append(datetime.fromisoformat(n).timestamp())
        except ValueError:
            night_base.append(0.0)
    out = []
    for pid, h in hits.items():
        a, b = divmod(pid, N)
        co = (masks[a] & masks[b]).bit_count()
        if co < min_nights or h < min_conf * co - 1e-9:
            continue
        ka, kb = job_keys[a], job_keys[b]
        if linked(ka, kb):
            continue
        ends, starts_b = [], []
        for t in range(T):
            ra, rb = runs[t].get(a), runs[t].get(b)
            if ra and rb and 0 <= rb[0] - ra[1] <= W:
                ends.append(ra[1] - night_base[t])
                starts_b.append(rb[0] - night_base[t])
        gaps = [y - x for x, y in zip(ends, starts_b)]
        corr = _tracking(ends, starts_b)
        lb = _lower_bound(corr, len(ends))
        if min_correlation is not None and (lb is None or lb < min_correlation):
            continue
        out.append({
            "a_job": ka, "b_job": kb, "a_flow": flow_of(ka), "nights_both_ran": co, "nights_hit": h,
            "confidence": round(h / co, 4), "median_gap_s": round(statistics.median(gaps), 1) if gaps else "",
            "tracking": "" if corr is None else round(corr, 3),
            "tracking_lb": "" if lb is None else round(lb, 3),
        })
    out.sort(key=lambda r: (-r["confidence"], -r["nights_both_ran"], r["a_job"], r["b_job"]))
    return out


def flows(estate: Estate):
    """Map a job key to its flow: the weakly connected component of the defined graph,
    named after its smallest job key. Jobs not in the definitions are their own flow."""
    parent = {k: k for k in estate.jobs}

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    for e in estate.edges:
        if e.src in parent and e.dst in parent:
            ra, rb = find(e.src), find(e.dst)
            if ra != rb:
                parent[ra] = rb
    for j in estate.jobs.values():
        if j.container and j.container in parent:
            ra, rb = find(j.key), find(j.container)
            if ra != rb:
                parent[ra] = rb
    name = {}
    for k in sorted(parent):
        name.setdefault(find(k), k)
    return lambda k: name[find(k)] if k in parent else k


Z_999 = 3.29  # two-sided 99.9%: we test a very large number of pairs


def _lower_bound(r, n):
    """Lower bound of the correlation (Fisher z, 99.9%). Penalises few nights:
    r = 0.85 over 13 nights is common by chance when you test a million pairs."""
    if r is None or n < 4:
        return None
    r = max(min(r, 0.999999), -0.999999)
    return math.tanh(math.atanh(r) - Z_999 / math.sqrt(n - 3))


def group_by_flow(rows):
    """One row per (flow of A, B): what a reviewer actually has to look at."""
    groups = {}
    for r in rows:
        g = groups.setdefault((r["a_flow"], r["b_job"]), {
            "a_flow": r["a_flow"], "b_job": r["b_job"], "pairs": 0, "best_a_job": r["a_job"],
            "best_confidence": r["confidence"], "best_tracking_lb": r["tracking_lb"]})
        g["pairs"] += 1
        key = (r["confidence"], r["tracking_lb"] if r["tracking_lb"] != "" else -2)
        best = (g["best_confidence"], g["best_tracking_lb"] if g["best_tracking_lb"] != "" else -2)
        if key > best:
            g.update(best_a_job=r["a_job"], best_confidence=r["confidence"], best_tracking_lb=r["tracking_lb"])
    return sorted(groups.values(), key=lambda g: (-g["best_confidence"], -g["pairs"], g["b_job"]))


def _tracking(ends, starts_b):
    """Correlation between A's end and B's start over the hit nights.

    Both are seconds since midnight of the order date, so the date itself does not
    dominate the correlation.

    If B waits for A, B's start moves with A's end and this is close to 1. If B simply
    starts at a fixed clock time that happens to fall just after A, it is close to 0.
    """
    if len(ends) < 3:
        return None
    try:
        return statistics.correlation(ends, starts_b)
    except statistics.StatisticsError:
        return None


CANDIDATE_FIELDS = ["a_job", "b_job", "a_flow", "nights_both_ran", "nights_hit", "confidence",
                    "median_gap_s", "tracking", "tracking_lb"]
GROUP_FIELDS = ["a_flow", "b_job", "pairs", "best_a_job", "best_confidence", "best_tracking_lb"]


def write_candidates(rows, path, fields=CANDIDATE_FIELDS):
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--history", required=True, help="run-history CSV (schema in ctminv/history.py)")
    ap.add_argument("--definitions", nargs="*", default=[], help="definition files; their edges are excluded")
    ap.add_argument("--window", type=float, default=15.0, help="minutes after A ends (default 15)")
    ap.add_argument("--min-confidence", type=float, default=0.9)
    ap.add_argument("--min-nights", type=int, default=10)
    ap.add_argument("--direct-only", action="store_true", help="exclude only direct edges A->B, not paths")
    ap.add_argument("--min-tracking", type=float, default=None,
                    help="optional extra filter: keep only pairs where B's start follows A's end, i.e. the "
                         "99.9%% lower bound of their correlation over the hit nights is >= this "
                         "(0.3 works well on the synthetic estate). Off by default.")
    ap.add_argument("--groups-out", default=None,
                    help="also write one row per (flow of A, B), default <out>-groups.csv")
    ap.add_argument("--out", default="timegap-candidates.csv")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args(argv)
    t0 = time.time()
    estate = load_definitions(args.definitions) if args.definitions else None
    job_keys, nights, runs = load_history(args.history, estate)
    t1 = time.time()
    rows = find_candidates(job_keys, nights, runs, estate, args.window, args.min_confidence,
                           args.min_nights, args.direct_only, verbose=args.verbose,
                           min_correlation=args.min_tracking)
    write_candidates(rows, args.out)
    groups = group_by_flow(rows)
    groups_out = args.groups_out or (args.out[:-4] if args.out.endswith(".csv") else args.out) + "-groups.csv"
    write_candidates(groups, groups_out, GROUP_FIELDS)
    print(f"{len(job_keys)} jobs, {len(nights)} nights, {sum(len(n) for n in runs)} job-nights; "
          f"load {t1 - t0:.1f}s, search {time.time() - t1:.1f}s")
    print(f"{len(rows)} candidate pairs -> {args.out}")
    print(f"{len(groups)} groups (flow of A, B) -> {groups_out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
