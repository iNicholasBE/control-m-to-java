import os

from conftest import REPO, fixture
from ctminv import parse_jil


def test_jil_fixture():
    est = parse_jil.parse_file(fixture("sample.jil"))
    assert "OLD_JOB" not in est.jobs                       # delete_job
    assert est.jobs["EOD_WATCH"].host == "app02"           # update_job
    assert est.jobs["EOD_BOX"].is_container                # job_type on the insert_job line (autorep -q style)
    assert est.jobs["EOD_LOAD"].container == "EOD_BOX"
    assert est.jobs["EOD_LOAD"].folder == "EOD_BOX"
    assert est.jobs["EOD_WATCH"].folder == ""
    e = {(x.src, x.dst, x.label) for x in est.edges}
    assert ("EOD_EXTRACT", "EOD_LOAD", "success") not in e  # label is the short form used
    assert ("EOD_EXTRACT", "EOD_LOAD", "s") in e
    assert ("EOD_LOAD", "EOD_REPORT", "s") in e
    assert ("EOD_EXTRACT", "EOD_REPORT", "d") in e         # look-back form d(job,12.00)
    assert ("EOD_LOAD", "EOD_REPORT", "f") in e
    assert ("EOD_REPORT", "MF_SORT", "s") in e
    assert est.jobs["EOD_EXTRACT"].external_refs == ["success(UPSTREAM_FEED^PRD)"]
    assert est.jobs["EOD_LOAD"].external_refs == ["value(LOAD_ENABLED)"]
    assert ("DB_CONNECTIONS", "virtual", "2") in est.jobs["EOD_LOAD"].resources
    assert ("LEDGER", "virtual", "1") in est.jobs["EOD_LOAD"].resources
    assert est.jobs["MF_SORT"].platform == "zos"
    assert "DB_CONNECTIONS" not in est.jobs                # insert_resource is not a job


def test_condition_parser():
    refs, ext = parse_jil.parse_condition("(s(A) AND success(B,01.00)) OR exitcode(C) = 4 | n(D) & v(X)=1 & t(E^PRD)")
    assert refs == [("A", "s"), ("B", "success"), ("C", "exitcode"), ("D", "n")]
    assert ext == ["t(E^PRD)", "value(X)"]


def test_reference_chain_jil():
    est = parse_jil.parse_file(os.path.join(REPO, "definitions", "autosys", "nightly-close.jil"))
    assert len(est.real_jobs()) == 25
    assert est.jobs["NC_BOX"].is_container
    assert len(est.edges) == 43
    assert len([x for x in est.edges if x.dst == "NC_AGGREGATE"]) == 20
    assert est.jobs["NC_WAIT_POSITIONS"].type == "FW"
    assert ("DB_CONNECTIONS", "virtual", "1") in est.jobs["NC_SCORE_P20"].resources
