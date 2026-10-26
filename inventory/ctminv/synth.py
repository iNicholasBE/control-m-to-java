"""Generate a synthetic Control-M estate and 90 nights of run history with a known answer.

Shape (all shares configurable):
  * folders are applications; each distributed folder runs on one agent host, some on two
  * single jobs: no events at all
  * in-domain chains of length 2..40 (short chains are more common), plus a few
    fan-out chains: head -> K partitions (K up to 500) -> join
  * cross-system edges between folders, including mainframe -> distributed and back
  * mainframe-only folders with Job:zOS:Member jobs and their own chains
  * a few chain heads wait for events that no job in the estate adds (another
    Control-M server, a manual order)
  * run frequency per chain: daily, weekdays, or weekly
  * planted hidden dependencies: B (a chain head or single job) really starts when A
    ends, but no event links them. About 70% wait for A on 93-100% of the nights, the
    rest on 80-90% (below the 90% threshold on purpose, to measure what timegap misses)

Writes into --out-dir:
  definitions.json   Automation API JSON, one object per folder (like `ctm deploy jobs::get`)
  history.csv        run history in the ctminv schema
  planted.csv        the truth: a_job, b_job, planned hit rate, realised hit rate
  synth-summary.json counts and timings

Usage: python -m ctminv synth [--jobs 9000] [--nights 90] [--seed 42] [--out-dir synth-out]
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import os
import random
import sys
import time
from collections import defaultdict
from datetime import date, datetime, timedelta


class Spec:
    def __init__(self, **kw):
        self.jobs = 9000
        self.nights = 90
        self.seed = 42
        self.start = date(2026, 7, 1)
        self.share_single = 0.22
        self.share_mainframe = 0.12
        self.fanout_chains = 6
        self.fanout_max = 500
        self.cross_edge_share = 0.03      # share of jobs that get one extra cross-folder edge
        self.external_event_share = 0.01  # share of chain heads waiting for an event nobody adds
        self.two_host_folder_share = 0.2
        self.planted = 150
        self.planted_min_rate = 0.80
        self.failure_rate = 0.005
        self.__dict__.update({k: v for k, v in kw.items() if v is not None})


def _chain_length(rng):
    # mostly short chains, a long tail up to 40
    return min(40, max(2, int(round(rng.paretovariate(1.6) * 2))))


def build(spec: Spec):
    rng = random.Random(spec.seed)
    jobs = {}        # key -> dict(name, folder, host, mf, freq, base_start, dur, type)
    folders = defaultdict(list)
    edges = []       # (a_key, b_key) event edges
    heads = []       # keys of jobs with a scheduled start (chain heads and singles)
    rank = {}
    external_wait = {}

    n_mf = int(spec.jobs * spec.share_mainframe)
    n_single = int(spec.jobs * spec.share_single)
    n_dist_chain = spec.jobs - n_mf - n_single

    n_dist_folders = max(1, n_dist_chain // 55)
    n_mf_folders = max(1, n_mf // 70)
    dist_folders = [f"APP{i:03d}" for i in range(n_dist_folders)]
    mf_folders = [f"MF{i:02d}" for i in range(n_mf_folders)]
    folder_hosts = {}
    for f in dist_folders:
        hosts = [f"{f.lower()}-agent"]
        if rng.random() < spec.two_host_folder_share:
            hosts.append(f"{f.lower()}-db-agent")
        folder_hosts[f] = hosts
    for f in mf_folders:
        folder_hosts[f] = ["MVSPROD1"]

    counter = [0]

    def new_job(folder, mf, freq, base_start, chain_pos, chain_base):
        counter[0] += 1
        name = f"{folder}_J{counter[0]:05d}"
        key = f"{folder}/{name}"
        hosts = folder_hosts[folder]
        jobs[key] = {
            "name": name, "folder": folder, "mf": mf, "freq": freq,
            "host": hosts[0] if len(hosts) == 1 or rng.random() < 0.7 else hosts[1],
            "base_start": base_start,
            "dur": max(10.0, min(5400.0, rng.lognormvariate(math.log(180), 1.0))),
        }
        rank[key] = (chain_base, chain_pos, counter[0])
        folders[folder].append(key)
        return key

    def freq_pick():
        r = rng.random()
        return "daily" if r < 0.80 else ("weekdays" if r < 0.95 else "weekly")

    def base_time():
        # minutes after 18:00 on the order date: a 12-hour batch window, 18:00 to 06:00
        return rng.uniform(0, 720)

    def make_chain(folder, mf, length):
        freq, base = freq_pick(), base_time()
        prev = None
        for pos in range(length):
            k = new_job(folder, mf, freq, base if pos == 0 else None, pos, base)
            if prev:
                edges.append((prev, k))
            else:
                heads.append(k)
            prev = k

    def make_fanout(folder, k_parts):
        freq, base = "daily", base_time()
        head = new_job(folder, False, freq, base, 0, base)
        heads.append(head)
        join = None
        parts = [new_job(folder, False, freq, None, 1, base) for _ in range(k_parts)]
        join = new_job(folder, False, freq, None, 2, base)
        for p in parts:
            edges.append((head, p))
            edges.append((p, join))
        return 2 + k_parts

    # fan-out chains first, in their own folders' budget
    budget = n_dist_chain
    fan_sizes = sorted([rng.randint(50, spec.fanout_max) for _ in range(spec.fanout_chains - 1)] + [spec.fanout_max])
    for i, k in enumerate(fan_sizes):
        if budget < k + 2:
            break
        budget -= make_fanout(dist_folders[i % len(dist_folders)], k)
    while budget >= 2:
        length = min(_chain_length(rng), budget)
        make_chain(rng.choice(dist_folders), False, length)
        budget -= length
    mf_budget = n_mf
    while mf_budget >= 2:
        length = min(_chain_length(rng), mf_budget)
        make_chain(rng.choice(mf_folders), True, length)
        mf_budget -= length
    for _ in range(n_single + budget + mf_budget):
        f = rng.choice(dist_folders)
        bt = base_time()
        k = new_job(f, False, freq_pick(), bt, 0, bt)
        jobs[k]["single"] = True
        heads.append(k)

    # cross-system edges: from a lower-ranked job to a higher-ranked one in another folder
    keys = list(jobs)
    non_single = [k for k in keys if not jobs[k].get("single")]
    mf_keys = [k for k in non_single if jobs[k]["mf"]]
    dist_keys = [k for k in non_single if not jobs[k]["mf"]]
    want = int(spec.jobs * spec.cross_edge_share)
    made, tries = 0, 0
    while made < want and tries < want * 50:
        tries += 1
        r = rng.random()
        if r < 0.6:
            a, b = rng.choice(dist_keys), rng.choice(dist_keys)
        elif r < 0.9:
            a, b = rng.choice(mf_keys), rng.choice(dist_keys)
        else:
            a, b = rng.choice(dist_keys), rng.choice(mf_keys)
        if jobs[a]["folder"] == jobs[b]["folder"]:
            continue
        if rank[a] >= rank[b]:
            a, b = b, a
        edges.append((a, b))
        made += 1

    for k in heads:
        if not jobs[k].get("single") and rng.random() < spec.external_event_share:
            external_wait[k] = f"EXT_{jobs[k]['name']}_READY"

    # planted hidden dependencies: B is a head (single or chain head) without in-edges
    has_in = {b for _, b in edges}
    b_pool = [k for k in heads if k not in has_in and k not in external_wait and not jobs[k]["mf"]]
    rng.shuffle(b_pool)
    planted = []
    used_b = set()
    for b in b_pool:
        if len(planted) >= spec.planted:
            break
        for _ in range(20):
            a = rng.choice(keys)
            if a == b or jobs[a]["folder"] == jobs[b]["folder"] or rank[a] >= rank[b] or jobs[a]["mf"]:
                continue
            if jobs[a]["freq"] != "daily":
                continue
            # most hidden dependencies are steady; about a third are flaky enough to fall under 90%
            rate = rng.uniform(0.93, 1.0) if rng.random() < 0.7 else rng.uniform(spec.planted_min_rate, 0.9)
            planted.append((a, b, round(rate, 3)))
            used_b.add(b)
            break
    return jobs, folders, edges, planted, external_wait


def definitions_json(jobs, folders, edges, external_wait):
    adds, waits = defaultdict(list), defaultdict(list)
    for a, b in edges:
        ev = f"{jobs[a]['name']}-TO-{jobs[b]['name']}"
        adds[a].append(ev)
        waits[b].append(ev)
    for k, ev in external_wait.items():
        waits[k].append(ev)
    doc = {}
    week = ["MON", "TUE", "WED", "THU", "FRI"]
    for f, keys in sorted(folders.items()):
        mf = jobs[keys[0]]["mf"]
        fobj = {"Type": "Folder", "ControlmServer": "CTM_MF" if mf else "CTM_DIST",
                "Application": f, "SubApplication": f"{f}_BATCH"}
        for k in keys:
            j = jobs[k]
            when = {"WeekDays": week if j["freq"] == "weekdays" else (["MON"] if j["freq"] == "weekly" else ["ALL"])}
            if j["base_start"] is not None:
                minutes = int(j["base_start"]) + 18 * 60
                when["FromTime"] = f"{(minutes // 60) % 24:02d}{minutes % 60:02d}"
            if mf:
                obj = {"Type": "Job:zOS:Member", "MemberLibrary": f"PROD.{f}.JCL", "Host": j["host"]}
            else:
                obj = {"Type": "Job:Command", "Command": f"/opt/{f.lower()}/bin/{j['name'].lower()}.sh",
                       "Host": j["host"], "RunAs": f"{f.lower()}usr"}
            obj.update({"Application": f, "SubApplication": f"{f}_BATCH", "When": when})
            if waits.get(k):
                obj["eventsToWaitFor"] = {"Type": "WaitForEvents", "Events": [{"Event": e} for e in waits[k]]}
                obj["eventsToDelete"] = {"Type": "DeleteEvents", "Events": [{"Event": e} for e in waits[k]]}
            if adds.get(k):
                obj["eventsToAdd"] = {"Type": "AddEvents", "Events": [{"Event": e} for e in adds[k]]}
            fobj[j["name"]] = obj
        doc[f] = fobj
    return doc


def simulate(spec: Spec, jobs, edges, planted):
    rng = random.Random(spec.seed + 1)
    preds = defaultdict(list)
    for a, b in edges:
        preds[b].append(a)
    hidden = {b: (a, rate) for a, b, rate in planted}
    # topological order over defined + planted edges
    succ = defaultdict(list)
    indeg = defaultdict(int)
    for a, b in edges:
        succ[a].append(b)
        indeg[b] += 1
    for a, b, _ in planted:
        succ[a].append(b)
        indeg[b] += 1
    order, queue = [], [k for k in jobs if indeg[k] == 0]
    while queue:
        k = queue.pop()
        order.append(k)
        for s in succ[k]:
            indeg[s] -= 1
            if indeg[s] == 0:
                queue.append(s)
    if len(order) != len(jobs):
        raise RuntimeError("cycle in synthetic graph")

    rows = []
    realised = defaultdict(lambda: [0, 0])  # (a,b) -> [hit nights, nights both ran]
    for n in range(spec.nights):
        od = spec.start + timedelta(days=n)
        dow = od.weekday()
        evening = datetime(od.year, od.month, od.day, 18, 0, 0).timestamp()
        start_t, end_t = {}, {}
        for k in order:
            j = jobs[k]
            if j["freq"] == "weekdays" and dow >= 5:
                continue
            if j["freq"] == "weekly" and dow != 0:
                continue
            ready = [end_t[p] for p in preds[k] if p in end_t]
            if j["base_start"] is not None:
                s = evening + j["base_start"] * 60 + rng.gauss(0, 180)
                if ready:
                    s = max(s, max(ready) + rng.uniform(1, 60))
            elif ready:
                s = max(ready) + rng.uniform(1, 60)
            else:
                # chain member whose predecessors did not run tonight: starts on its own
                s = evening + rng.uniform(0, 720) * 60
            if k in hidden:
                a, rate = hidden[k]
                if a in end_t:
                    realised[(a, k)][1] += 1
                    if rng.random() < rate:
                        s = end_t[a] + rng.uniform(20, 600)
                    else:
                        # the night it did not wait (file came another way, operator started it):
                        # it runs at a later fallback time
                        s = max(s, end_t[a] + rng.uniform(1200, 5400))
                    if 0 <= s - end_t[a] <= 900:
                        realised[(a, k)][0] += 1
            d = j["dur"] * rng.lognormvariate(0, 0.25)
            host = j["host"]
            if rng.random() < spec.failure_rate:
                fail_end = s + d * rng.uniform(0.1, 0.9)
                rows.append((j["name"], j["folder"], od.isoformat(), s, fail_end, "NOTOK", host))
                s2 = fail_end + rng.uniform(300, 1800)
                e = s2 + d
                rows.append((j["name"], j["folder"], od.isoformat(), s2, e, "OK", host))
            else:
                e = s + d
                rows.append((j["name"], j["folder"], od.isoformat(), s, e, "OK", host))
            start_t[k], end_t[k] = s, e
    return rows, realised


def write_outputs(spec, out_dir, jobs, folders, edges, planted, external_wait, rows, realised):
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "definitions.json"), "w", encoding="utf-8") as fh:
        json.dump(definitions_json(jobs, folders, edges, external_wait), fh, indent=1)
    with open(os.path.join(out_dir, "history.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["job_name", "folder", "order_date", "start_time", "end_time", "status", "host"])
        for name, folder, od, s, e, st, host in rows:
            w.writerow([name, folder, od, datetime.fromtimestamp(s).isoformat(timespec="seconds"),
                        datetime.fromtimestamp(e).isoformat(timespec="seconds"), st, host])
    with open(os.path.join(out_dir, "planted.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["a_job", "b_job", "planned_rate", "nights_both_ran", "nights_hit", "realised_rate"])
        for a, b, rate in planted:
            hit, both = realised[(a, b)]
            w.writerow([a, b, rate, both, hit, round(hit / both, 4) if both else ""])


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jobs", type=int, default=9000)
    ap.add_argument("--nights", type=int, default=90)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--planted", type=int, default=150)
    ap.add_argument("--share-single", type=float)
    ap.add_argument("--share-mainframe", type=float)
    ap.add_argument("--fanout-max", type=int)
    ap.add_argument("--out-dir", default="synth-out")
    args = ap.parse_args(argv)
    spec = Spec(jobs=args.jobs, nights=args.nights, seed=args.seed, planted=args.planted,
                share_single=args.share_single, share_mainframe=args.share_mainframe, fanout_max=args.fanout_max)
    t0 = time.time()
    jobs, folders, edges, planted, external_wait = build(spec)
    t1 = time.time()
    rows, realised = simulate(spec, jobs, edges, planted)
    t2 = time.time()
    write_outputs(spec, args.out_dir, jobs, folders, edges, planted, external_wait, rows, realised)
    t3 = time.time()
    summary = {
        "jobs": len(jobs), "folders": len(folders), "event_edges": len(edges), "planted": len(planted),
        "history_rows": len(rows), "nights": spec.nights, "seed": spec.seed,
        "seconds": {"build": round(t1 - t0, 2), "simulate": round(t2 - t1, 2), "write": round(t3 - t2, 2),
                    "total": round(t3 - t0, 2)},
    }
    with open(os.path.join(args.out_dir, "synth-summary.json"), "w") as fh:
        json.dump(summary, fh, indent=2)
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
