import csv
import json

from ctminv import evaluate_timegap, synth, timegap
from ctminv.classify import Classifier, distribution
from ctminv.model import load_definitions


def test_small_synthetic_estate_end_to_end(tmp_path):
    out = tmp_path / "s"
    assert synth.main(["--jobs", "600", "--nights", "40", "--planted", "20", "--fanout-max", "60",
                       "--seed", "7", "--out-dir", str(out)]) == 0
    summary = json.load(open(out / "synth-summary.json"))
    assert summary["jobs"] == 600
    est = load_definitions([str(out / "definitions.json")])
    assert len(est.real_jobs()) == 600
    dist = distribution(Classifier(est).run())
    assert all(dist[b] > 0 for b in ("single", "in_domain", "cross_system", "mainframe_only"))
    keys, nights, runs = timegap.load_history(str(out / "history.csv"), est)
    assert len(nights) == 40
    planted = list(csv.DictReader(open(out / "planted.csv")))
    plain = evaluate_timegap.evaluate(timegap.find_candidates(keys, nights, runs, est), planted, est)
    assert plain["planted"] == 20
    assert plain["pairs: recall, visible planted"] == 1.0
    tracked = evaluate_timegap.evaluate(
        timegap.find_candidates(keys, nights, runs, est, min_correlation=0.3), planted, est)
    assert tracked["candidate_pairs"] < plain["candidate_pairs"]
    assert tracked["groups: precision"] > plain["groups: precision"]
