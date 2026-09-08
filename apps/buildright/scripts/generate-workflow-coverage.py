#!/usr/bin/env python3
"""Generate the BuildRight portfolio's workflow-coverage register.

Reads the erpplans checkout's operational workflow corpus — every `## W<id>`
workflow header across the 569 PA files (5,426 unique workflows) — joins each
workflow with its criticality tier (workflow-criticality-classification.md)
and its requirement links (requirement-workflow-matrix.md), derives a coverage
state from the committed requirement coverage
(requirements-coverage/coverage.json — run generate-coverage.py first), merges
the hand-maintained pins (../workflow-map.json), and writes:

  workflow-coverage/coverage.json  — machine-readable: per-workflow state
  workflow-coverage/matrix.md      — human-readable matrix

States (the workflow-level twin of §12.3's requirement states — every one of
the 5,426 workflows is in exactly one):

  pinned     executed by the e2e corpus — the entry names the suite file(s)
  partial    app metadata exercises the workflow via a claimed requirement,
             with no dedicated suite pin yet (the requirement's own evidence
             and its gaps travel with the derived note)
  uncovered  visible and wave-attributed — the register's honest empty state

Usage:
  python3 generate-workflow-coverage.py [path-to-erpplans-checkout]

The erpplans path defaults to ~/erpplans. Re-run after touching
workflow-map.json or requirements-coverage/, or after a wave lands; the
generated files are committed so coverage changes review like code.
"""

import json
import re
import subprocess
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
APP = HERE.parent
REPO = APP.parent.parent
DEFAULT_ERPPLANS = Path("~/erpplans").expanduser()

# The workflow universe: `## W<id>. <title>` headers in the PA files — exactly
# the 5,426 canonical workflows (upstream's own count). The classification
# register carries 23 additional `###` parent/summary sub-workflow rows
# (W2A, W9A, W54A, …) that receive their own classification; the register
# tracks all 5,449 rows with sub-workflows marked, and reconciles the canon
# tier split over the full set.
WF_HEADER = re.compile(r"^## (W\d+[A-Z]*)\. (.*?)\s*$")
# Tier rows: `| W<id> | title | rationale |` — the tier comes from the nearest
# preceding tier heading (## Tier N: / ### Tier N Additions / #### Tier N …).
TIER_HEADING = (re.compile(r"^## Tier (\d):"), re.compile(r"^### Tier (\d) Additions"),
                re.compile(r"^#### Tier (\d)\b"))
TIER_ROW = re.compile(r"^\| (W\d+[A-Z]*) \|")
# Requirement→workflow links: `| REQ | title | M | primary refs | supporting refs |`
# (R14's third column is a Target spec value, not a priority — unused here).
MATRIX_ROW = re.compile(r"^\| ([A-Z]{2,4}-\d{3}[a-z]?) \| .+? \| .+? \| (.*?) \| (.*?) \|\s*$")
WF_REF = re.compile(r"W\d+[A-Z]*")

# The upstream canons this register reconciles against — a mismatch means the
# upstream corpus moved: re-run the generator and re-point consciously.
CANON_CANONICAL = 5426
CANON_REGISTER_ROWS = 5449
CANON_SUBWORKFLOWS = 23
CANON_TIERS = {1: 1396, 2: 3295, 3: 758}


def git_rev(repo: Path) -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"], cwd=repo,
            capture_output=True, text=True, check=True).stdout.strip()
    except Exception:
        return "unknown"


def parse_universe(erpplans: Path) -> dict:
    """{workflow id: {title, vs, pa}} from every PA file's `## W` headers."""
    universe = {}
    for pa_file in sorted((erpplans / "01-model-company" / "workflows").glob("VS-*/PA-*.md")):
        vs_dir = pa_file.parent.name
        for line in pa_file.read_text(encoding="utf-8").splitlines():
            m = WF_HEADER.match(line)
            if m:
                universe[m.group(1)] = {
                    "title": m.group(2),
                    "vs": vs_dir,
                    "pa": pa_file.stem,
                }
    return universe


def parse_tiers(erpplans: Path) -> dict:
    """{workflow id: (tier, title)} from the classification register — all 5,449
    rows (first row wins); the title feeds the 23 sub-workflow rows that have no
    `##` header of their own."""
    tiers = {}
    tier = None
    headings = [re.compile(r"^## Tier (\d):"), re.compile(r"^### Tier (\d) Additions"),
                re.compile(r"^#### Tier (\d)\b")]
    path = erpplans / "01-model-company" / "workflows" / "workflow-criticality-classification.md"
    for line in path.read_text(encoding="utf-8").splitlines():
        matched = [int(h.match(line).group(1)) for h in headings if h.match(line)]
        if matched:
            tier = matched[0]
            continue
        m = TIER_ROW.match(line)
        if m and tier:
            cells = [c.strip() for c in line.split("|")]
            tiers.setdefault(m.group(1), (tier, cells[2] if len(cells) > 2 else ""))
    return tiers


def parse_requirement_links(erpplans: Path) -> dict:
    """{workflow id: {primary: [req ids], supporting: [req ids]}} — inverted
    from requirement-workflow-matrix.md; refs like `W9A.6a` or `W2A.5–6`
    normalize to their base workflow id."""
    links = {}
    path = erpplans / "01-model-company" / "requirement-workflow-matrix.md"
    for line in path.read_text(encoding="utf-8").splitlines():
        m = MATRIX_ROW.match(line)
        if not m:
            continue
        req_id, primary_cell, supporting_cell = m.group(1), m.group(2), m.group(3)
        for kind, cell in (("primary", primary_cell), ("supporting", supporting_cell)):
            for wf_id in sorted(set(WF_REF.findall(cell))):
                entry = links.setdefault(wf_id, {"primary": [], "supporting": []})
                if req_id not in entry[kind]:
                    entry[kind].append(req_id)
    return links


def derive_wave(req_ids: list, links: dict, req_state: dict, prefix_waves: dict):
    """The most concrete wave attributable to a workflow: the minimum integer
    wave over its linked requirements' prefixes; a string wave ('edge',
    'platform') if no integer applies; None when nothing is planned yet."""
    waves = []
    for req_id in req_ids:
        prefix = req_id.rsplit("-", 1)[0]
        wave = prefix_waves.get(prefix, {}).get("wave")
        if wave is not None:
            waves.append(wave)
    ints = [w for w in waves if isinstance(w, int)]
    if ints:
        return min(ints)
    if waves:
        return waves[0]
    return None


def main() -> int:
    erpplans = Path(sys.argv[1]).expanduser() if len(sys.argv) > 1 else DEFAULT_ERPPLANS
    base = erpplans / "01-model-company"
    if not (base / "workflows").is_dir():
        print(f"erpplans checkout not found: {base}", file=sys.stderr)
        return 1

    universe = parse_universe(erpplans)
    if len(universe) != CANON_CANONICAL:
        print(f"canonical workflow universe is {len(universe)}, canon is {CANON_CANONICAL} — "
              f"the upstream corpus moved; re-run and re-point the register",
              file=sys.stderr)
        return 1

    register = parse_tiers(erpplans)
    tiers = {wf: t for wf, (t, _) in register.items()}
    tier_counts = Counter(tiers.values())
    if len(register) != CANON_REGISTER_ROWS or dict(sorted(tier_counts.items())) != CANON_TIERS:
        print(f"classification register is {len(register)} rows "
              f"{dict(sorted(tier_counts.items()))}, canon is {CANON_REGISTER_ROWS} "
              f"{CANON_TIERS} — the upstream corpus moved; re-run and re-point",
              file=sys.stderr)
        return 1
    subworkflow_ids = sorted(w for w in register if w not in universe)
    if len(subworkflow_ids) != CANON_SUBWORKFLOWS:
        print(f"sub-workflow rows are {len(subworkflow_ids)}, canon is "
              f"{CANON_SUBWORKFLOWS} — the upstream corpus moved", file=sys.stderr)
        return 1

    links = parse_requirement_links(erpplans)

    req_cov_path = APP / "requirements-coverage" / "coverage.json"
    if not req_cov_path.exists():
        print("requirements-coverage/coverage.json missing — run generate-coverage.py "
              "first: workflow states derive from requirement states", file=sys.stderr)
        return 1
    req_cov = json.loads(req_cov_path.read_text(encoding="utf-8"))
    req_rows = {r["id"]: r for r in req_cov["requirements"]}
    prefix_waves = json.loads((APP / "coverage-map.json").read_text(encoding="utf-8"))["prefixes"]

    hand = json.loads((APP / "workflow-map.json").read_text(encoding="utf-8"))
    hand_workflows = hand.get("workflows", {})
    unknown_hand = sorted(w for w in hand_workflows if w not in universe
                          and w not in register)
    if unknown_hand:
        print(f"workflow-map.json names workflows outside the universe: {unknown_hand}",
              file=sys.stderr)
        return 2

    workflows = []
    for wf_id in sorted(set(universe) | set(register)):
        canonical = wf_id in universe
        info = universe.get(wf_id)
        row = {
            "id": wf_id,
            "title": info["title"] if info else register[wf_id][1],
            "vs": info["vs"] if info else None,
            "pa": info["pa"] if info else None,
            "tier": tiers[wf_id],
            "subWorkflow": not canonical,
        }
        linked = links.get(wf_id, {"primary": [], "supporting": []})
        row["requirements"] = {k: sorted(v) for k, v in linked.items()}

        entry = hand_workflows.get(wf_id)
        if entry:
            row["status"] = entry["status"]
            row["suites"] = sorted(entry.get("suites", []))
            row["app"] = entry.get("app")
            row["wave"] = entry.get("wave", derive_wave(
                linked["primary"] + linked["supporting"], links, req_rows, prefix_waves))
            row["note"] = entry.get("note", "")
            if row["status"] == "pinned" and not row["suites"]:
                print(f"{wf_id} is pinned but names no suite", file=sys.stderr)
                return 2
        else:
            claimed = [r for r in linked["primary"]
                       if req_rows.get(r, {}).get("status") in ("covered", "partial")]
            if claimed:
                req = req_rows[claimed[0]]
                row["status"] = "partial"
                row["suites"] = []
                row["app"] = req.get("app")
                row["wave"] = derive_wave(claimed, links, req_rows, prefix_waves)
                row["note"] = (f"exercises {claimed[0]} ({req['status']} via {row['app']}); "
                               f"no dedicated suite pin")
            else:
                row["status"] = "uncovered"
                row["suites"] = []
                row["app"] = None
                row["wave"] = derive_wave(
                    linked["primary"] + linked["supporting"], links, req_rows, prefix_waves)
                row["note"] = (f"rides uncovered requirement(s) "
                               f"{', '.join((linked['primary'] + linked['supporting'])[:3])}"
                               if linked else
                               "no requirement-workflow matrix mapping yet (the upstream "
                               "matrix covers the core value streams incrementally)")
        workflows.append(row)

    by_status = Counter(w["status"] for w in workflows)
    by_tier = Counter(w["tier"] for w in workflows)
    by_wave = Counter(str(w["wave"]) for w in workflows)
    vs_stats = {}
    for w in workflows:
        if not w["vs"]:
            continue
        stat = vs_stats.setdefault(w["vs"], Counter())
        stat["total"] += 1
        stat[w["status"]] += 1

    coverage = {
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "erpplansRev": git_rev(erpplans),
        "novaforgeRev": git_rev(REPO),
        "sources": {
            "universe": "01-model-company/workflows/VS-*/PA-*.md (## W headers)",
            "tiers": "01-model-company/workflows/workflow-criticality-classification.md",
            "links": "01-model-company/requirement-workflow-matrix.md",
            "claims": "apps/buildright/workflow-map.json + requirements-coverage/coverage.json",
        },
        "totals": {
            "all": {"total": len(workflows), **{k: by_status.get(k, 0)
                                                for k in ("pinned", "partial", "uncovered")}},
            "canonical": sum(1 for w in workflows if not w["subWorkflow"]),
            "subWorkflows": sum(1 for w in workflows if w["subWorkflow"]),
            "byTier": {f"tier{t}": by_tier.get(t, 0) for t in (1, 2, 3)},
            "byWave": dict(sorted(by_wave.items())),
        },
        "register": {"rows": len(register), **{f"tier{t}": tier_counts.get(t, 0)
                                             for t in (1, 2, 3)},
                     "note": "the classification register's 5,449 rows = the 5,426 "
                             "canonical ## workflows + 23 ### parent/summary sub-workflow "
                             "rows; the canon tier split 1,396/3,295/758 counts all rows"},
        "valueStreams": {vs: dict(stat) for vs, stat in sorted(vs_stats.items())},
        "workflows": workflows,
    }
    out_json = APP / "workflow-coverage" / "coverage.json"
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(coverage, indent=2, ensure_ascii=False) + "\n",
                        encoding="utf-8")

    lines = [
        "# BuildRight Portfolio — Workflow Coverage Register",
        "",
        f"> Generated by `scripts/generate-workflow-coverage.py` — do not edit by hand;"
        f" edit `../workflow-map.json` (and `../coverage-map.json`) and re-run.",
        f"> Source: erpplans @ `{coverage['erpplansRev']}`, novaforge @ `{coverage['novaforgeRev']}`.",
        "",
        "| Scope | Total |",
        "|---|---|",
        f"| Workflow register rows (5,426 canonical + 23 sub-workflows) | {len(workflows)} |",
        f"| Pinned (executed by the e2e corpus) | {by_status.get('pinned', 0)} |",
        f"| Partial (claimed requirement, no dedicated pin) | {by_status.get('partial', 0)} |",
        f"| Uncovered (visible, wave-attributed) | {by_status.get('uncovered', 0)} |",
        "",
        f"Tier split (universe): " + ", ".join(
            f"Tier {t}: {by_tier.get(t, 0)}" for t in (1, 2, 3)) + ".", "",
        "## Pinned — the corpus's workflow pins",
        "",
        "| Workflow | Title | Tier | Suite pin(s) | Note |",
        "|---|---|---|---|---|",
    ]
    for w in workflows:
        if w["status"] == "pinned":
            lines.append(f"| {w['id']} | {w['title']} | {w['tier']} |"
                         f" {', '.join(w['suites'])} | {w['note'][:160]} |")

    lines += ["", "## Partial — claimed requirements without a dedicated workflow pin",
              "", "| Workflow | Title | Tier | App | Requirement(s) | Note |", "|---|---|---|---|---|---|"]
    for w in workflows:
        if w["status"] == "partial":
            reqs = ", ".join(w["requirements"]["primary"])
            lines.append(f"| {w['id']} | {w['title']} | {w['tier']} | {w['app'] or '—'} |"
                         f" {reqs} | {w['note'][:160]} |")

    lines += ["", "## By value stream (188)", "",
              "| VS | Total | Pinned | Partial | Uncovered |", "|---|---|---|---|---|"]
    for vs, stat in sorted(vs_stats.items()):
        lines.append(f"| {vs} | {stat['total']} | {stat.get('pinned', 0)} |"
                     f" {stat.get('partial', 0)} | {stat.get('uncovered', 0)} |")

    out_md = APP / "workflow-coverage" / "matrix.md"
    out_md.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"workflows: {len(workflows)} (pinned {by_status.get('pinned', 0)},"
          f" partial {by_status.get('partial', 0)}, uncovered {by_status.get('uncovered', 0)};"
          f" tiers {by_tier.get(1, 0)}/{by_tier.get(2, 0)}/{by_tier.get(3, 0)})")
    print(f"wrote {out_json}")
    print(f"wrote {out_md}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
