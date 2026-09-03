#!/usr/bin/env bash
# The observability gate (forty-first pass): PHASE-0 §8 pins the Grafana baseline
# as "one row per service — availability (up), HTTP p95, JVM heap", and every
# later phase continues it (PHASE-1 §7; PHASE-4's board carried its new services'
# rows). Nothing enforced it: Phase 6 landed the Integration and File services
# with Prometheus scrape jobs but no dashboard row anywhere, and the Data
# Runtime row had been colliding with the Metadata row's grid position since
# Phase 1 — visible only by reading JSON. Two checks, both mechanical:
#
#   1. Row-per-service: every novaforge-* scrape job in prometheus.yml must have
#      a row in the Phase 0 board carrying the three baseline panels (up, p95,
#      heap), each targeting that exact job. A service added without its row
#      fails here, with the missing job named.
#
#   2. Grid honesty: within every provisioned dashboard, no two panels may
#      occupy the same (x, y) grid position — Grafana's grid is absolute, so a
#      collision is two panels rendering on top of each other (the Data Runtime
#      / Metadata overlap this gate was born from).
#
# Bite-proven both ways (the twenty-fifth pass's rule: a gate check without a
# bite-proof is a print statement): delete a service row or duplicate a grid
# position and this gate fails naming the defect.
set -uo pipefail
cd "$(dirname "$0")/../.."   # repo root

status=0
fail() { echo "OBSERVABILITY FAIL: $*" >&2; status=1; }

PROM=deploy/compose/observability/prometheus/prometheus.yml
DASH_DIR=deploy/compose/observability/grafana/provisioning/dashboards

[ -f "$PROM" ] || { fail "prometheus.yml not found at $PROM"; exit 1; }
[ -d "$DASH_DIR" ] || { fail "dashboard dir not found at $DASH_DIR"; exit 1; }

python3 - "$PROM" "$DASH_DIR" <<'PY' || status=1
import json, re, sys
from pathlib import Path

prom_path, dash_dir = Path(sys.argv[1]), Path(sys.argv[2])
failed = False

def fail(msg):
    global failed
    failed = True
    print(f"OBSERVABILITY FAIL: {msg}", file=sys.stderr)

# --- scrape jobs: the service universe the baseline must cover ---------------
prom = prom_path.read_text()
jobs = re.findall(r"^\s*-\s*job_name:\s*(\S+)\s*$", prom, re.M)
service_jobs = sorted(j for j in jobs if j.startswith("novaforge-") and "kafka" not in j)
if not service_jobs:
    fail(f"no novaforge-* service scrape jobs found in {prom_path}")

# --- the Phase 0 board: one row per service, three baseline panels each ------
board_path = dash_dir / "novaforge-phase0.json"
board = json.loads(board_path.read_text())
rows = [p for p in board["panels"] if p.get("type") == "row"]
panels_by_row = []
current = None
for p in board["panels"]:
    if p.get("type") == "row":
        current = {"row": p, "panels": []}
        panels_by_row.append(current)
    elif current is not None:
        current["panels"].append(p)

def row_titles():
    return {r["row"].get("title", "") for r in panels_by_row}

# Map each service job to the row whose panels target it.
for job in service_jobs:
    candidates = []
    for entry in panels_by_row:
        exprs = [t.get("expr", "") for p in entry["panels"] for t in p.get("targets", [])]
        if any(f'job="{job}"' in e for e in exprs):
            candidates.append(entry)
    if not candidates:
        fail(f"service {job} is scraped but has no dashboard row in {board_path.name} "
             f"(PHASE-0 §8: one row per service — availability, HTTP p95, JVM heap)")
        continue
    entry = candidates[0]
    titles = [p.get("title", "") for p in entry["panels"]]
    for needed in ("Availability (up)", "HTTP server requests p95 (s)", "JVM heap used (B)"):
        if needed not in titles:
            fail(f"service {job}: row '{entry['row'].get('title')}' is missing the "
                 f"'{needed}' baseline panel (PHASE-0 §8)")
    types = sorted(p.get("type", "") for p in entry["panels"])
    if types != ["stat", "timeseries", "timeseries"]:
        fail(f"service {job}: row '{entry['row'].get('title')}' does not carry the "
             f"stat + two timeseries baseline shape (found {types})")

# --- grid honesty: no (x, y) collisions within any provisioned dashboard -----
for path in sorted(dash_dir.glob("*.json")):
    d = json.loads(path.read_text())
    seen = {}
    for p in d.get("panels", []):
        g = p.get("gridPos") or {}
        key = (g.get("x"), g.get("y"))
        if key in seen:
            fail(f"{path.name}: panels '{seen[key]}' and '{p.get('title')}' collide at "
                 f"grid (x={key[0]}, y={key[1]}) — Grafana's grid is absolute; they "
                 f"render on top of each other")
        else:
            seen[key] = p.get("title")

if failed:
    sys.exit(1)
print(f"observability: {len(service_jobs)} service rows × 3 baseline panels across "
      f"{len(list(dash_dir.glob('*.json')))} boards; no grid collisions")
PY

exit $status
