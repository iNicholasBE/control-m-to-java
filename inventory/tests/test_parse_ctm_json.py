import os

from conftest import REPO, fixture
from ctminv import parse_ctm_json
from ctminv.model import load_definitions


def edges(est):
    return {(e.src, e.dst, e.kind) for e in est.edges}


def test_flow_sequence_and_defaults():
    est = parse_ctm_json.parse_file(fixture("ctm_flow.json"))
    assert set(est.jobs) == {"SampleFlow/CommandJob", "SampleFlow/ScriptJob", "SampleFlow/LastJob"}
    cmd = est.jobs["SampleFlow/CommandJob"]
    assert cmd.type == "Job:Command"
    assert cmd.host == "linux-agent-01"            # from Defaults
    assert cmd.application == "SampleApp"           # from Defaults
    assert cmd.when["FromTime"] == "0300"           # from Defaults.Job.When
    assert est.jobs["SampleFlow/LastJob"].host == "linux-agent-02"  # job overrides default
    assert edges(est) == {
        ("SampleFlow/CommandJob", "SampleFlow/ScriptJob", "flow"),
        ("SampleFlow/ScriptJob", "SampleFlow/LastJob", "flow"),
    }


def test_events_resources_subfolders():
    est = parse_ctm_json.parse_file(fixture("ctm_events.json"))
    e = edges(est)
    assert ("PAYMENTS/LOAD", "PAYMENTS/POST", "event") in e          # key name of the block does not matter
    assert ("PAYMENTS/POST", "GL/BOOK", "event") in e                # Event:Add inside an If block
    assert ("PAYMENTS/POST_DONE", "PAYMENTS/REPORTS", "event") in e  # SubFolder-level event
    assert ("PAYMENTS/REPORTS/R1", "PAYMENTS/REPORTS/R2", "flow") in e
    assert ("MAINFRAME/EXTRACT", "MAINFRAME/SORT", "event") in e
    assert est.dangling_in == {"PAYMENTS/LOAD": ["EXT_FEED_READY"]}
    load = est.jobs["PAYMENTS/LOAD"]
    assert ("DB_POOL", "Resource:Pool", "2") in load.resources
    assert ("LEDGER", "Resource:Lock", "Exclusive") in load.resources
    assert est.jobs["PAYMENTS/POST"].del_events == ["LOAD-TO-POST"]
    assert est.jobs["PAYMENTS/REPORTS"].is_container
    assert est.jobs["PAYMENTS/REPORTS/R1"].container == "PAYMENTS/REPORTS"
    assert est.jobs["PAYMENTS/REPORTS/R1"].folder == "PAYMENTS"
    assert est.jobs["PAYMENTS/REPORTS/R1"].application == "PAYMENTS"  # inherited from the folder
    assert est.jobs["MAINFRAME/EXTRACT"].platform == "zos"
    assert len(est.real_jobs()) == 9


def test_reference_chain_definition():
    path = os.path.join(REPO, "definitions", "control-m", "nightly-close.json")
    est = load_definitions([path])
    assert len(est.real_jobs()) == 26
    assert len(est.edges) == 44
    assert est.dangling_in == {}
    types = {j.type for j in est.jobs.values()}
    assert {"Job:FileWatcher:Create", "Job:Command", "Job:SLAManagement"} <= types
    score = est.jobs["NIGHTLY_CLOSE/SCORE_P07"]
    assert ("DB_CONNECTIONS", "Resource:Pool", "1") in score.resources
    fan_in = [e for e in est.edges if e.dst == "NIGHTLY_CLOSE/AGGREGATE_RISK"]
    assert len(fan_in) == 20
