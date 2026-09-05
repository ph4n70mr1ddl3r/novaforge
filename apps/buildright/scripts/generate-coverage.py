#!/usr/bin/env python3
"""Generate the BuildRight portfolio's requirement-coverage matrix.

Reads the erpplans checkout's requirement catalog (erp-requirements.md), merges
it with the hand-maintained coverage claims (../coverage-map.json), and writes:

  requirements-coverage/coverage.json  — machine-readable: per-requirement + per-prefix state
  requirements-coverage/matrix.md      — human-readable matrix with totals

Usage:
  python3 generate-coverage.py [path-to-erpplans-checkout]

The erpplans path defaults to ~/erpplans. Re-run after touching
coverage-map.json or after a wave lands; the generated files are committed so
coverage changes review like code.
"""

import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
APP = HERE.parent
REPO = APP.parent.parent
DEFAULT_ERPPLANS = Path("~/erpplans").expanduser()

ROW = re.compile(
    r"^\| ([A-Z]{2,4}-\d{3}) \| (.+?) \| (Must Have|Should Have|Nice to Have) \|")
HEADING = re.compile(r"^## (R\d+)\. (.+)$")


def git_rev(repo: Path) -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"], cwd=repo,
            capture_output=True, text=True, check=True).stdout.strip()
    except Exception:
        return "unknown"


def parse_requirements(erpplans: Path):
    """[(req_id, title, priority, section)] in document order."""
    path = erpplans / "01-model-company" / "erp-requirements.md"
    rows, section = [], "unknown"
    for line in path.read_text(encoding="utf-8").splitlines():
        heading = HEADING.match(line)
        if heading:
            section = f"{heading.group(1)} {heading.group(2).strip()}"
            continue
        row = ROW.match(line)
        if row:
            rows.append((row.group(1), row.group(2).strip(),
                         row.group(3), section))
    return rows


def main() -> int:
    erpplans = Path(sys.argv[1]).expanduser() if len(sys.argv) > 1 else DEFAULT_ERPPLANS
    catalog = erpplans / "01-model-company" / "erp-requirements.md"
    if not catalog.exists():
        print(f"erpplans catalog not found: {catalog}", file=sys.stderr)
        return 1

    claims = json.loads((APP / "coverage-map.json").read_text(encoding="utf-8"))
    prefixes = claims["prefixes"]
    overrides = claims.get("requirements", {})

    requirements = []
    unknown_prefixes = set()
    for req_id, title, priority, section in parse_requirements(erpplans):
        prefix = req_id.rsplit("-", 1)[0]
        entry = overrides.get(req_id)
        if entry:
            status, app, note = entry["status"], entry.get("app"), entry.get("evidence", "")
            wave = prefixes.get(prefix, {}).get("wave", "?")
        else:
            p = prefixes.get(prefix)
            if p is None:
                unknown_prefixes.add(prefix)
                status, app, note, wave = "uncovered", None, "no coverage claim — review the map", "?"
            else:
                status, app, wave = p["status"], p.get("app"), p.get("wave", "?")
                note = p.get("note", "")
        requirements.append({
            "id": req_id, "title": title, "priority": priority,
            "section": section, "prefix": prefix,
            "status": status, "app": app, "wave": wave, "note": note,
        })

    def prefix_of(req):
        return req["prefix"]

    by_prefix = {}
    for req in requirements:
        by_prefix.setdefault(prefix_of(req), []).append(req)

    prefix_stats = {}
    for prefix, rows in sorted(by_prefix.items()):
        counts = {"covered": 0, "partial": 0, "uncovered": 0}
        for row in rows:
            counts[row["status"]] += 1
        default = prefixes.get(prefix, {})
        prefix_stats[prefix] = {
            "total": len(rows), **counts,
            "wave": default.get("wave", "?"),
            "app": default.get("app"),
            "note": default.get("note", ""),
        }

    totals = {k: sum(s[k] for s in prefix_stats.values())
              for k in ("covered", "partial", "uncovered")}
    must = [r for r in requirements if r["priority"] == "Must Have"]
    must_totals = {k: sum(1 for r in must if r["status"] == k)
                   for k in ("covered", "partial", "uncovered")}

    coverage = {
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "erpplansRev": git_rev(erpplans),
        "novaforgeRev": git_rev(REPO),
        "source": "01-model-company/erp-requirements.md",
        "claims": "apps/buildright/coverage-map.json",
        "totals": {"all": {"total": len(requirements), **totals},
                   "mustHave": {"total": len(must), **must_totals}},
        "prefixes": prefix_stats,
        "requirements": requirements,
    }
    out_json = APP / "requirements-coverage" / "coverage.json"
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(coverage, indent=2) + "\n", encoding="utf-8")

    lines = [
        "# BuildRight Portfolio — Requirement Coverage Matrix",
        "",
        f"> Generated by `scripts/generate-coverage.py` — do not edit by hand; edit"
        f" `../coverage-map.json` and re-run.",
        f"> Source: erpplans `01-model-company/erp-requirements.md` @ `{coverage['erpplansRev']}`,"
        f" novaforge @ `{coverage['novaforgeRev']}`.",
        "",
        "| Scope | Total | Covered | Partial | Uncovered |",
        "|---|---|---|---|---|",
        f"| All requirements | {len(requirements)} | {totals['covered']} | {totals['partial']} | {totals['uncovered']} |",
        f"| Must Have only | {len(must)} | {must_totals['covered']} | {must_totals['partial']} | {must_totals['uncovered']} |",
        "",
        "## By prefix",
        "",
        "| Prefix | Wave | Total | Covered | Partial | Uncovered | Note |",
        "|---|---|---|---|---|---|---|",
    ]
    for prefix, stat in prefix_stats.items():
        lines.append(
            f"| {prefix} | {stat['wave']} | {stat['total']} | {stat['covered']} |"
            f" {stat['partial']} | {stat['uncovered']} | {stat['note'][:160]} |")

    lines += ["", "## Claimed coverage (covered + partial, with evidence)", "",
              "| Req | P | Status | App | Evidence / named gaps |", "|---|---|---|---|---|"]
    for req in requirements:
        if req["status"] != "uncovered":
            prio = {"Must Have": "M", "Should Have": "S"}.get(req["priority"], "N")
            evidence = req["note"] if req["status"] == "partial" and not any(
                r["id"] == req["id"] for r in []) else req["note"]
            lines.append(f"| {req['id']} | {prio} | {req['status']} |"
                         f" {req['app'] or '—'} | {evidence} |")

    if unknown_prefixes:
        lines += ["", f"## Prefixes with no coverage-map entry ({len(unknown_prefixes)})", "",
                  "- " + ", ".join(sorted(unknown_prefixes))]

    out_md = APP / "requirements-coverage" / "matrix.md"
    out_md.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"requirements: {len(requirements)} "
          f"(covered {totals['covered']}, partial {totals['partial']}, "
          f"uncovered {totals['uncovered']}; must-have: "
          f"{must_totals['covered']}/{must_totals['partial']}/{must_totals['uncovered']})")
    print(f"wrote {out_json}")
    print(f"wrote {out_md}")
    if unknown_prefixes:
        print(f"WARNING: prefixes missing from coverage-map.json: "
              f"{', '.join(sorted(unknown_prefixes))}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
