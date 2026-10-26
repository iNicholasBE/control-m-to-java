import csv
import json
import random
from datetime import datetime, timedelta

from conftest import fixture
from ctminv import history, timegap
from ctminv.model import load_definitions, Estate, Job, Edge


def write_history(path, nights=30, seed=3):
    """Small history with a known answer.

    HIDDEN_A -> HIDDEN_B   B starts 2-8 min after A ends on every night (hidden)
    LINK_A   -> LINK_B     same timing, but the definitions link them (must be excluded)
    FLAKY_A  -> FLAKY_B    B follows A on only 80% of the nights (below 90%)
    CLOCK_A, CLOCK_B       both on fixed clock times, B 5 min after A ends every night
                           (a coincidence: passes the 90% rule, fails the tracking filter)
    RARE_A   -> RARE_B     follows perfectly but they only share 6 nights (< min nights)
    """
    rng = random.Random(seed)
    rows = []
    day0 = datetime(2026, 9, 1)

    def run(name, od, s, dur):
        rows.append([name, "F", od.date().isoformat(), s.isoformat(timespec="seconds"),
                     (s + timedelta(seconds=dur)).isoformat(timespec="seconds"), "OK", "h1"])
        return s + timedelta(seconds=dur)

    for n in range(nights):
        od = day0 + timedelta(days=n)
        ev = od.replace(hour=22)
        a_end = run("HIDDEN_A", od, ev + timedelta(minutes=rng.uniform(-30, 30)), rng.uniform(600, 1800))
        run("HIDDEN_B", od, a_end + timedelta(seconds=rng.uniform(120, 480)), 300)
        l_end = run("LINK_A", od, ev + timedelta(minutes=rng.uniform(-30, 30)), rng.uniform(600, 1800))
        run("LINK_B", od, l_end + timedelta(seconds=rng.uniform(60, 300)), 300)
        f_end = run("FLAKY_A", od, ev + timedelta(minutes=rng.uniform(-30, 30)), rng.uniform(600, 1800))
        gap = rng.uniform(60, 300) if n % 5 else rng.uniform(3600, 5400)
        run("FLAKY_B", od, f_end + timedelta(seconds=gap), 300)
        c_end = run("CLOCK_A", od, ev.replace(hour=23) + timedelta(seconds=rng.uniform(-20, 20)), 600)
        run("CLOCK_B", od, ev.replace(hour=23, minute=15) + timedelta(seconds=rng.uniform(-60, 60)), 60)
        if n < 6:
            r_end = run("RARE_A", od, ev.replace(hour=20), 600)
            run("RARE_B", od, r_end + timedelta(seconds=60), 60)
    with open(path, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(history.FIELDS)
        w.writerows(rows)


def estate_with_link():
    est = Estate()
    for name in ("HIDDEN_A", "HIDDEN_B", "LINK_A", "LINK_B", "FLAKY_A", "FLAKY_B",
                 "CLOCK_A", "CLOCK_B", "RARE_A", "RARE_B"):
        est.add_job(Job(key=f"F/{name}", name=name, folder="F"))
    est.edges.append(Edge("F/LINK_A", "F/LINK_B", "event", "LINK_A-TO-LINK_B"))
    return est


def pairs(rows):
    return {(r["a_job"], r["b_job"]) for r in rows}


def test_known_answer(tmp_path):
    h = tmp_path / "h.csv"
    write_history(h)
    est = estate_with_link()
    keys, nights, runs = timegap.load_history(str(h), est)
    rows = timegap.find_candidates(keys, nights, runs, est)
    p = pairs(rows)
    assert ("F/HIDDEN_A", "F/HIDDEN_B") in p
    assert ("F/LINK_A", "F/LINK_B") not in p           # defined edge
    assert ("F/FLAKY_A", "F/FLAKY_B") not in p         # 80% < 90%
    assert ("F/RARE_A", "F/RARE_B") not in p           # 6 nights < 10
    assert ("F/CLOCK_A", "F/CLOCK_B") in p             # the rule alone cannot tell
    hidden = next(r for r in rows if (r["a_job"], r["b_job"]) == ("F/HIDDEN_A", "F/HIDDEN_B"))
    assert hidden["confidence"] == 1.0 and hidden["nights_both_ran"] == 30
    assert 120 <= hidden["median_gap_s"] <= 480


def test_tracking_filter_removes_clock_coincidence(tmp_path):
    h = tmp_path / "h.csv"
    write_history(h)
    est = estate_with_link()
    keys, nights, runs = timegap.load_history(str(h), est)
    rows = timegap.find_candidates(keys, nights, runs, est, min_correlation=0.3)
    p = pairs(rows)
    assert ("F/HIDDEN_A", "F/HIDDEN_B") in p
    assert ("F/CLOCK_A", "F/CLOCK_B") not in p


def test_paths_versus_direct_edges(tmp_path):
    h = tmp_path / "h.csv"
    write_history(h)
    est = estate_with_link()
    # HIDDEN_A -> X -> HIDDEN_B in the definitions: excluded by default, kept with direct_only
    est.add_job(Job(key="F/X", name="X", folder="F"))
    est.edges += [Edge("F/HIDDEN_A", "F/X", "event", "e1"), Edge("F/X", "F/HIDDEN_B", "event", "e2")]
    keys, nights, runs = timegap.load_history(str(h), est)
    assert ("F/HIDDEN_A", "F/HIDDEN_B") not in pairs(timegap.find_candidates(keys, nights, runs, est))
    assert ("F/HIDDEN_A", "F/HIDDEN_B") in pairs(
        timegap.find_candidates(keys, nights, runs, est, direct_only=True))


def test_window_boundary_and_reruns(tmp_path):
    # B starts exactly 15:00 after A ends -> hit; a failed run plus rerun counts as one long run
    h = tmp_path / "h.csv"
    rows = []
    for n in range(12):
        od = datetime(2026, 9, 1) + timedelta(days=n)
        s = od.replace(hour=22, minute=n)
        rows.append(["A", "", od.date().isoformat(), s.isoformat(), (s + timedelta(minutes=5)).isoformat(), "NOTOK", ""])
        rows.append(["A", "", od.date().isoformat(), (s + timedelta(minutes=10)).isoformat(),
                     (s + timedelta(minutes=20)).isoformat(), "OK", ""])
        b = s + timedelta(minutes=35)
        rows.append(["B", "", od.date().isoformat(), b.isoformat(), (b + timedelta(minutes=1)).isoformat(), "OK", ""])
    with open(h, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(history.FIELDS)
        w.writerows(rows)
    keys, nights, runs = timegap.load_history(str(h))
    got = {(r["a_job"], r["b_job"]): r for r in timegap.find_candidates(keys, nights, runs)}
    assert got[("A", "B")]["median_gap_s"] == 900.0


def test_cli_and_groups(tmp_path):
    h = tmp_path / "h.csv"
    write_history(h)
    out = tmp_path / "c.csv"
    assert timegap.main(["--history", str(h), "--out", str(out)]) == 0
    assert (tmp_path / "c-groups.csv").exists()
    rows = list(csv.DictReader(open(out)))
    assert ("F/HIDDEN_A", "F/HIDDEN_B") in {(r["a_job"], r["b_job"]) for r in rows}  # no definitions: folder/name


def test_history_from_ctm_status_json():
    doc = {"statuses": [
        {"jobId": "ctm:0001", "name": "NIGHTLY_CLOSE", "folder": "NIGHTLY_CLOSE", "type": "Folder",
         "status": "Ended OK", "startTime": "Oct 27, 2026, 1:00:00 AM", "endTime": "Oct 27, 2026, 5:10:00 AM",
         "orderDate": "261027", "host": ""},
        {"jobId": "ctm:0002", "name": "EXTRACT_POSITIONS", "folder": "NIGHTLY_CLOSE", "type": "Command",
         "status": "Ended OK", "startTime": "Oct 27, 2026, 1:02:11 AM", "endTime": "Oct 27, 2026, 1:09:40 AM",
         "orderDate": "261027", "host": "agent1", "numberOfRuns": 1},
        {"jobId": "ctm:0003", "name": "SCORE_P01", "folder": "NIGHTLY_CLOSE", "type": "Command",
         "status": "Ended Not OK", "startTime": "20261027011000", "endTime": "20261027011500",
         "orderDate": "261027", "host": "agent1", "numberOfRuns": 1},
        {"jobId": "ctm:0004", "name": "AGGREGATE_RISK", "folder": "NIGHTLY_CLOSE", "type": "Command",
         "status": "Wait Condition", "startTime": "", "endTime": "", "orderDate": "261027"},
    ]}
    rows = list(history.ctm_status_to_rows(doc))
    assert [r["job_name"] for r in rows] == ["EXTRACT_POSITIONS", "SCORE_P01"]
    assert rows[0]["order_date"] == "2026-10-27"
    assert rows[0]["start_time"] == "2026-10-27T01:02:11"
    assert rows[1]["status"] == "NOTOK"


def test_history_from_autorep_summary():
    text = """Job Name Last Start Last End ST/Ex Run/Ntry Pri/Xit
____________________________ ____________________ ____________________ _____ ________ _______
NC_BOX 10/27/2026 01:00:01 10/27/2026 05:12:44 SU 197/1 0
NC_EXTRACT 10/27/2026 01:02:11 10/27/2026 01:09:40 SU 197/1 0
NC_SCORE_P01 10/27/2026 01:10:02 10/27/2026 01:15:00 FA 197/2 1
NC_AGGREGATE ----- ----- AC 197/0
NC_LATE 10/27/2026 13:00:00 10/27/2026 13:05:00 SU/NE 12/1 0
"""
    rows = list(history.autorep_to_rows(text, day_starts_at=12, box_of={"NC_EXTRACT": "NC_BOX"}))
    assert [r["job_name"] for r in rows] == ["NC_BOX", "NC_EXTRACT", "NC_SCORE_P01", "NC_LATE"]
    assert rows[1]["order_date"] == "2026-10-26"   # before 12:00 -> previous business date
    assert rows[1]["folder"] == "NC_BOX"
    assert rows[2]["status"] == "NOTOK"
    assert rows[3]["order_date"] == "2026-10-27"
