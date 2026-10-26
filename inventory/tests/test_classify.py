import csv
import os

from conftest import REPO, fixture
from ctminv.classify import Classifier, distribution, main
from ctminv.model import load_definitions


def buckets(path_list, **kw):
    rows = Classifier(load_definitions(path_list), **kw).run()
    return {r["job_key"]: r for r in rows}


def test_four_buckets_control_m_json():
    b = buckets([fixture("ctm_events.json")])
    assert {k: v["bucket"] for k, v in b.items()} == {
        "PAYMENTS/LOAD": "cross_system",        # waits for an event nobody in the estate adds
        "PAYMENTS/POST": "cross_system",        # adds an event a job in folder GL waits for
        "PAYMENTS/HOUSEKEEP": "single",
        "PAYMENTS/POST_DONE": "in_domain",
        "PAYMENTS/REPORTS/R1": "in_domain",     # inherits the SubFolder's edge
        "PAYMENTS/REPORTS/R2": "in_domain",
        "GL/BOOK": "cross_system",
        "MAINFRAME/EXTRACT": "mainframe_only",
        "MAINFRAME/SORT": "mainframe_only",
    }
    assert "no analysed job" in b["PAYMENTS/LOAD"]["reason"]
    assert "GL" in b["PAYMENTS/POST"]["reason"]


def test_domain_by_application_and_regex():
    b = buckets([fixture("ctm_events.json")], domain="application")
    assert b["PAYMENTS/POST"]["domain"] == "PAYMENTS"
    assert b["GL/BOOK"]["domain"] == "GENERAL_LEDGER"
    assert b["GL/BOOK"]["bucket"] == "cross_system"
    b = buckets([fixture("ctm_events.json")], domain=r"regex:^([A-Z]+)")
    assert b["PAYMENTS/POST"]["domain"] == "POST"
    assert b["PAYMENTS/POST"]["bucket"] == "cross_system"   # LOAD -> POST now crosses domains


def test_host_crossing_is_configurable():
    b = buckets([fixture("ctm_flow.json")])
    assert b["SampleFlow/LastJob"]["bucket"] == "cross_system"
    assert "host" in b["SampleFlow/LastJob"]["reason"]
    b = buckets([fixture("ctm_flow.json")], ignore_host=True)
    assert {v["bucket"] for v in b.values()} == {"in_domain"}


def test_xml_and_jil():
    b = buckets([fixture("ctm_export.xml")])
    assert b["FIN-GL/GL-LOAD"]["bucket"] == "cross_system"        # fed by a mainframe job
    assert b["FIN-GL/GL-POST"]["bucket"] == "in_domain"
    assert b["FIN-GL/GL-ARCHIVE"]["bucket"] == "single"
    assert b["FIN-REPORTS/DAILY/DAILY-PDF"]["bucket"] == "cross_system"
    assert b["CORE/CORE010"]["bucket"] == "cross_system"           # mainframe feeding distributed
    j = buckets([fixture("sample.jil")])
    assert j["ADHOC_CLEANUP"]["bucket"] == "single"
    assert j["EOD_WATCH"]["bucket"] == "single"
    assert j["EOD_EXTRACT"]["bucket"] == "cross_system"            # ^PRD: another instance
    assert j["EOD_LOAD"]["bucket"] == "cross_system"               # value(GLOBAL)
    assert j["MF_SORT"]["bucket"] == "cross_system"
    assert "EOD_BOX" not in j                                      # containers are not classified


def test_mainframe_by_host_name():
    est = load_definitions([fixture("sample.jil")])
    est.jobs["MF_SORT"].platform = ""          # no job type hint left, only the machine name
    rows = {r["job_key"]: r for r in Classifier(est).run()}
    assert "mainframe" in rows["EOD_REPORT"]["reason"]


def test_reference_chain_is_one_domain():
    path = os.path.join(REPO, "definitions", "control-m", "nightly-close.json")
    rows = Classifier(load_definitions([path])).run()
    assert distribution(rows)["in_domain"] == 26


def test_cli_writes_csv(tmp_path, capsys):
    out = tmp_path / "b.csv"
    assert main([fixture("ctm_events.json"), "--out", str(out)]) == 0
    printed = capsys.readouterr().out
    assert "cross_system" in printed and "total" in printed
    rows = list(csv.DictReader(open(out)))
    assert len(rows) == 9
    assert set(rows[0]) >= {"job_key", "bucket", "reason", "domain"}
