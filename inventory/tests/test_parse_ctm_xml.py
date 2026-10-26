from conftest import fixture
from ctminv import parse_ctm_xml


def test_xml_export():
    est = parse_ctm_xml.parse_file(fixture("ctm_export.xml"))
    keys = set(est.jobs)
    assert {"FIN-GL/GL-LOAD", "FIN-GL/GL-POST", "FIN-GL/GL-ALERT", "FIN-GL/GL-ARCHIVE",
            "FIN-REPORTS/DAILY", "FIN-REPORTS/DAILY/DAILY-PDF", "CORE/CORE010"} == keys
    e = {(x.src, x.dst) for x in est.edges}
    assert ("CORE/CORE010", "FIN-GL/GL-LOAD") in e        # OUTCOND SIGN="ADD" -> INCOND
    assert ("FIN-GL/GL-LOAD", "FIN-GL/GL-POST") in e      # SIGN="+"
    assert ("FIN-GL/GL-POST", "FIN-GL/GL-ALERT") in e     # DOCOND inside ON
    assert ("FIN-GL/GL-LOAD", "FIN-REPORTS/DAILY") in e   # SUB_FOLDER INCOND
    load = est.jobs["FIN-GL/GL-LOAD"]
    assert load.del_events == ["CORE-EXTRACT-OK"]          # SIGN="-" deletes, adds nothing
    assert ("DB_CONNECTIONS", "Resource:Pool", "1") in load.resources
    assert ("GL_LEDGER", "Resource:Lock", "Exclusive") in load.resources
    assert load.host == "lnxapp01" and load.application == "FIN" and load.sub_application == "GL"
    assert est.jobs["CORE/CORE010"].platform == "zos"     # MEMNAME used as the name, PLATFORM=MVS
    assert est.jobs["FIN-REPORTS/DAILY"].is_container
    assert est.jobs["FIN-REPORTS/DAILY/DAILY-PDF"].application == "FIN"  # inherited from SMART_FOLDER


def test_unknown_elements_are_ignored():
    est = parse_ctm_xml.parse_string(
        '<DEFTABLE><FOLDER FOLDER_NAME="F"><SOMETHING_NEW X="1"/>'
        '<JOB JOBNAME="J"><VARIABLE NAME="%%A" VALUE="1"/><SHOUT WHEN="NOTOK"/></JOB></FOLDER></DEFTABLE>')
    assert list(est.jobs) == ["F/J"]


def test_old_table_format():
    est = parse_ctm_xml.parse_string(
        '<DEFTABLE><TABLE TABLE_NAME="T1"><JOB JOBNAME="A"><OUTCOND NAME="A-OK" SIGN="+"/></JOB>'
        '<JOB JOBNAME="B"><INCOND NAME="A-OK"/></JOB></TABLE></DEFTABLE>')
    assert [(e.src, e.dst) for e in est.edges] == [("T1/A", "T1/B")]
