"""Parse Control-M XML definitions (``exportdeffolder`` / ``deffolder`` format).

BMC does not publish the full schema in a form we could download (the
``Folder.xsd`` / ``deftable.dtd`` ship with Control-M/EM), so this is a
*tolerant* parser built from public examples, see inventory/SOURCES.md. It
accepts every container name we have seen:

    DEFTABLE > FOLDER | SMART_FOLDER | TABLE | SMART_TABLE | SCHED_TABLE | SCHED_GROUP
             > SUB_FOLDER (nested) > JOB

and reads per JOB: JOBNAME (or MEMNAME), APPLICATION, SUB_APPLICATION (or
GROUP), NODEID, TASKTYPE, scheduling attributes, and the children INCOND,
OUTCOND (SIGN "+"/"ADD" adds, "-"/"DEL" deletes), DOCOND inside ON blocks
(conditional adds), QUANTITATIVE (pool) and CONTROL (lock, TYPE E or S).
Unknown elements and attributes are ignored. A folder with
PLATFORM="MVS" / "z/OS" / "MAINFRAME" marks its jobs as mainframe.

Usage: python -m ctminv.parse_ctm_xml FILE.xml [--edges]
"""
from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET

from .model import Estate, Job

FOLDER_TAGS = {"FOLDER", "SMART_FOLDER", "TABLE", "SMART_TABLE", "SCHED_TABLE", "SCHED_GROUP"}
SUBFOLDER_TAGS = {"SUB_FOLDER", "SUB_TABLE"}
MAINFRAME_PLATFORMS = {"MVS", "Z/OS", "ZOS", "OS/390", "MAINFRAME"}
WHEN_ATTRS = ("DAYS", "WEEKDAYS", "DAYSCAL", "WEEKSCAL", "CONFCAL", "DAYS_AND_OR", "TIMEFROM", "TIMETO",
              "SHIFT", "SHIFTNUM", "RETRO", "MAXWAIT", "CYCLIC", "INTERVAL")
ADD_SIGNS = {"+", "ADD", "A"}
DEL_SIGNS = {"-", "DEL", "D"}


def _tag(el) -> str:
    t = el.tag
    return t.split("}", 1)[1].upper() if "}" in t else t.upper()


def _name(el) -> str:
    for a in ("FOLDER_NAME", "TABLE_NAME", "JOBNAME", "SCHED_TABLE", "NAME", "MEMNAME"):
        v = el.get(a)
        if v:
            return v.strip()
    return "UNNAMED"


def _collect(el, job: Job) -> None:
    for child in el:
        t = _tag(child)
        if t == "INCOND":
            n = (child.get("NAME") or "").strip()
            if n and n not in job.in_events:
                job.in_events.append(n)
        elif t == "OUTCOND":
            n = (child.get("NAME") or "").strip()
            sign = (child.get("SIGN") or "+").strip().upper()
            if not n:
                continue
            if sign in DEL_SIGNS:
                job.del_events.append(n)
            elif n not in job.out_events:
                job.out_events.append(n)
        elif t == "ON":
            for do in child.iter():
                if _tag(do) == "DOCOND":
                    n = (do.get("NAME") or "").strip()
                    sign = (do.get("SIGN") or "+").strip().upper()
                    if n and sign in ADD_SIGNS and n not in job.out_events:
                        job.out_events.append(n)
        elif t == "QUANTITATIVE":
            job.resources.append(((child.get("NAME") or "").strip(), "Resource:Pool", (child.get("QUANT") or "").strip()))
        elif t == "CONTROL":
            kind = (child.get("TYPE") or "").strip().upper()
            job.resources.append(((child.get("NAME") or "").strip(), "Resource:Lock",
                                  "Exclusive" if kind.startswith("E") else "Shared"))
        elif t in ("RULE_BASED_CALENDARS", "RULE_BASED_CALENDAR"):
            job.when.setdefault("RuleBasedCalendars", []).append(child.get("NAME", ""))


class _Parser:
    def __init__(self):
        self.estate = Estate()

    def folder(self, el, top, prefix, platform, inherited_app, container):
        tag = _tag(el)
        name = _name(el)
        if tag in FOLDER_TAGS:
            top = name
            prefix = name
            platform = (el.get("PLATFORM") or platform or "").strip()
        else:  # sub folder: a container node of its own
            prefix = f"{prefix}/{name}"
            node = Job(key=prefix, name=name, folder=top, type=tag, source="ctm-xml",
                       container=container, is_container=True,
                       application=el.get("APPLICATION", inherited_app) or "",
                       sub_application=el.get("SUB_APPLICATION", "") or "",
                       host=el.get("NODEID", "") or "",
                       platform="zos" if platform.upper() in MAINFRAME_PLATFORMS else "")
            _collect(el, node)
            self.estate.add_job(node)
            container = prefix
        app = el.get("APPLICATION", inherited_app) or inherited_app
        for child in el:
            t = _tag(child)
            if t in SUBFOLDER_TAGS or t in FOLDER_TAGS:
                self.folder(child, top, prefix, platform, app, container)
            elif t == "JOB":
                self.job(child, top, prefix, platform, app, container)

    def job(self, el, top, prefix, platform, app, container):
        name = (el.get("JOBNAME") or el.get("MEMNAME") or "UNNAMED").strip()
        tasktype = (el.get("TASKTYPE") or "").strip()
        is_mf = platform.upper() in MAINFRAME_PLATFORMS
        job = Job(
            key=f"{prefix}/{name}" if prefix else name,
            name=name,
            folder=top,
            type=tasktype,
            host=(el.get("NODEID") or "").strip(),
            application=(el.get("APPLICATION") or app or "").strip(),
            sub_application=(el.get("SUB_APPLICATION") or el.get("GROUP") or "").strip(),
            platform="zos" if is_mf else "",
            source="ctm-xml",
            container=container,
            when={a: el.get(a) for a in WHEN_ATTRS if el.get(a) is not None},
        )
        _collect(el, job)
        self.estate.add_job(job)


def parse_string(text: str, link: bool = True) -> Estate:
    root = ET.fromstring(text)
    p = _Parser()
    roots = [root] if _tag(root) in FOLDER_TAGS else list(root)
    for el in roots:
        t = _tag(el)
        if t in FOLDER_TAGS or t in SUBFOLDER_TAGS:
            p.folder(el, top="", prefix="", platform="", inherited_app="", container="")
        elif t == "JOB":
            p.job(el, "", "", "", "", "")
    if link:
        p.estate.link()
    return p.estate


def parse_file(path: str, link: bool = True) -> Estate:
    with open(path, "rb") as fh:
        data = fh.read()
    # Exports are often ISO-8859-1 and say so in the prolog; ElementTree honours that when given bytes.
    root = ET.fromstring(data)
    return parse_string(ET.tostring(root, encoding="unicode"), link)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="+")
    ap.add_argument("--edges", action="store_true")
    args = ap.parse_args(argv)
    from .cli import print_estate
    from .model import load_definitions
    print_estate(load_definitions(args.files), edges=args.edges)
    return 0


if __name__ == "__main__":
    sys.exit(main())
