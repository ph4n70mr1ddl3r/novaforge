#!/usr/bin/env bash
# The chart gate (twenty-first pass): lint + render every chart, keep the infra
# chart's embedded files byte-honest against their compose-stack sources, and —
# the check that would have caught two real defects earlier — verify DNS
# consistency across the rendered output: every host the charts' env wiring
# references must be a Service the same render creates. The nineteenth pass
# wired env values without a resolvability check (the twentieth's placeholder
# check validated names, not DNS); the release-prefixed service names the
# charts used before this pass left every inter-service URL unresolvable under
# any real release name.
set -uo pipefail
cd "$(dirname "$0")/../.."   # repo root

status=0
fail() { echo "CHART-GATE FAIL: $*" >&2; status=1; }

HELM="${HELM:-helm}"
command -v "$HELM" >/dev/null 2>&1 || { echo "helm not found (HELM=$HELM)" >&2; exit 2; }

render_dir="$(mktemp -d)"
trap 'rm -rf "$render_dir"' EXIT

# 1) every chart lints and renders
for chart in deploy/helm/novaforge-*/Chart.yaml; do
  dir=$(dirname "$chart")
  name=$(basename "$dir")
  "$HELM" lint "$dir" >/dev/null 2>&1 || fail "helm lint $name"
  "$HELM" template gate "$dir" > "$render_dir/$name.yaml" 2>/dev/null \
    || fail "helm template $name"
done
echo "charts: lint + render ok ($(ls "$render_dir" | wc -l) charts)"

# 1b) the umbrella chart — the composition an actual `helm upgrade` deploys
# (skaffold's chartPath, the documented image.tag flow). The novaforge-* glob
# above cannot match it; a broken umbrella (pin drift against a sibling chart,
# wiring that only breaks when the subcharts render under one release) passed
# CI and died at first real deploy. file:// dependencies vendor from the
# CURRENT sibling trees, so dependency build doubles as the version-drift
# check; the vendored copies are gate-local and cleaned up after.
umbrella=deploy/helm/novaforge
umbrella_ok=1
"$HELM" dependency build "$umbrella" >/dev/null 2>&1 || {
  fail "helm dependency build novaforge (umbrella pins vs sibling charts drifted?)"
  umbrella_ok=0
}
if [ "$umbrella_ok" -eq 1 ]; then
  "$HELM" lint "$umbrella" >/dev/null 2>&1 || fail "helm lint novaforge (umbrella)"
  "$HELM" template gate "$umbrella" > "$render_dir/novaforge-umbrella.yaml" 2>/dev/null \
    || fail "helm template novaforge (umbrella)"
fi
rm -rf "$umbrella/charts" "$umbrella/Chart.lock"
[ "$umbrella_ok" -eq 1 ] && echo "umbrella chart: vendored, linted, rendered"

# 2) the infra chart's embedded files are byte-mirrors of their sources
cmp -s deploy/helm/novaforge-infra/files/postgres-init.sh \
       deploy/postgres-init/01-databases.sh \
  || fail "files/postgres-init.sh drifted from deploy/postgres-init/01-databases.sh"
cmp -s deploy/helm/novaforge-infra/files/novaforge-realm.json \
       deploy/compose/keycloak/novaforge-realm.json \
  || fail "files/novaforge-realm.json drifted from deploy/compose/keycloak/novaforge-realm.json"
cmp -s deploy/helm/novaforge-infra/files/tempo.yml \
       deploy/compose/observability/tempo/tempo.yml \
  || fail "files/tempo.yml drifted from deploy/compose/observability/tempo/tempo.yml"
echo "infra embedded files: byte-identical to their compose-stack sources"

# 2b) URL-env parity: every NOVAFORGE_*URL a service's application.yaml can
# consume, its chart must set. The inverse of the DNS leg below — that check
# proves every env the charts SET resolves; it says nothing about wiring a
# service consumes but its chart never ships. That gap is not hypothetical:
# the data-runtime dials script hooks at localhost:8084 in every cluster
# deploy because its chart never set NOVAFORGE_SCRIPT_ENGINE_URL (thirtieth
# pass) — and its default-deny egress policy is derived from the same env
# list, so the missing var blocked the flow twice over. localhost defaults
# only work where services run as host JVMs beside compose infra (the dev
# launcher); the charts are the in-cluster wiring, and a peer URL left to its
# localhost default silently aims the pod at itself.
for chart in deploy/helm/novaforge-*/; do
  name=$(basename "$chart")
  [ "$name" = "novaforge-infra" ] && continue
  [ "$name" = "novaforge" ] && continue
  svc=${name#novaforge-}
  res="services/$svc/src/main/resources/application.yaml"
  # the data-runtime artifact lives under api/ (its four-module split)
  [ -f "$res" ] || res="services/$svc/api/src/main/resources/application.yaml"
  if [ ! -f "$res" ]; then
    fail "URL parity: no application.yaml found for chart $name (expected $res)"
    continue
  fi
  consumed=$(grep -oE 'NOVAFORGE_[A-Z0-9_]*URL' "$res" | sort -u)
  set_vars=$(grep -oE 'NOVAFORGE_[A-Z0-9_]*URL' "$chart/values.yaml" | sort -u)
  for var in $consumed; do
    grep -qx "$var" <<< "$set_vars" \
      || fail "URL parity: $name consumes $var (its application.yaml) but values.yaml never sets it — the peer URL falls back to localhost in-cluster"
  done
done
[ "$status" -eq 0 ] && echo "url-env parity: every consumed NOVAFORGE_*URL is set by its service's chart"

# 3) DNS consistency: every env-referenced host resolves to a rendered Service
python3 - "$render_dir" <<'PY' || status=1
import glob, os, re, sys, yaml

render_dir = sys.argv[1]
services = set()
accounts = set()
policies = {}
budgets = {}
pods = []
env_hosts = set()
# initialized BEFORE the walk: a later reset would wipe every collection-phase
# failure (the Job-restartPolicy and service-links checks printed their FAILs
# and still exited 0 — found by bite-proofing, twenty-fifth pass)
ok = True
# A bare value is a HOST only when its env NAME says so — anything else
# (KC_DB: postgres, KC_DB_USERNAME: keycloak) is a vendor string or credential,
# and treating it as a host is how false positives drown the signal.
HOST_ENV = re.compile(r"^(NOVAFORGE_[A-Z0-9_]*HOST|NOVAFORGE_KAFKA|NOVAFORGE_POSTGRES_HOST)$")

def walk(node):
    if isinstance(node, dict):
        if "name" in node and "value" in node and isinstance(node.get("value"), str):
            yield node["name"], node["value"]
        for v in node.values():
            yield from walk(v)
    elif isinstance(node, list):
        for v in node:
            yield from walk(v)

for path in glob.glob(render_dir + "/*.yaml"):
    chart = os.path.basename(path)[:-5]
    for doc in open(path).read().split("\n---"):
        if not doc.strip():
            continue
        try:
            obj = yaml.safe_load(doc)
        except Exception as e:
            print("CHART-GATE FAIL: unparseable render %s: %s" % (path, e))
            sys.exit(1)
        if not isinstance(obj, dict):
            continue
        meta = obj.get("metadata") or {}
        kind = obj.get("kind")
        if kind == "Service":
            services.add(meta.get("name"))
        elif kind == "ServiceAccount":
            accounts.add(meta.get("name"))
        elif kind == "NetworkPolicy":
            types = set((obj.get("spec") or {}).get("policyTypes") or [])
            policies[chart] = policies.get(chart, set()) | types
        elif kind == "PodDisruptionBudget":
            budgets[chart] = budgets.get(chart, 0) + 1
        elif kind in ("Deployment", "StatefulSet", "Job", "CronJob", "ReplicaSet"):
            # a CronJob nests its pod template under jobTemplate — spec.template on a
            # CronJob is the job template, not the pod spec
            if kind == "CronJob":
                job_spec = ((obj.get("spec") or {}).get("jobTemplate") or {}).get("spec") or {}
                template = job_spec.get("template")
            else:
                template = (obj.get("spec") or {}).get("template")
            ps = (template or {}).get("spec") if isinstance(template, dict) else None
            if isinstance(ps, dict):
                # the flags ride the tuple: a post-phase check reading a leaked walk
                # variable validates the LAST walked pod N times, not these pods
                pods.append((chart, meta.get("name"),
                             ps.get("serviceAccountName"),
                             ps.get("automountServiceAccountToken"),
                             ps.get("enableServiceLinks")))
                if ps.get("enableServiceLinks") is not False:
                    print("CHART-GATE FAIL: %s/%s does not disable k8s service-link env injection" % (chart, meta.get("name")))
                    ok = False
                if kind == "Job" and ps.get("restartPolicy") not in ("OnFailure", "Never"):
                    # the API server rejects a Job without it — found the hard way
                    # when a live install refused exactly this (twenty-fourth pass)
                    print("CHART-GATE FAIL: %s/%s Job lacks a valid restartPolicy" % (chart, meta.get("name")))
                    ok = False
                if kind == "CronJob" and ps.get("restartPolicy") not in ("OnFailure", "Never"):
                    # the same API contract one level down: the CronJob's pod template
                    # (inside jobTemplate) requires it too
                    print("CHART-GATE FAIL: %s/%s CronJob pod template lacks a valid restartPolicy" % (chart, meta.get("name")))
                    ok = False
        for env_name, value in walk(obj):
            m = re.match(r"https?://([a-z0-9.-]+)(?::\d+)?", value)
            if m:
                env_hosts.add(m.group(1))
            elif HOST_ENV.match(env_name):
                env_hosts.add(value.strip().split(":")[0])

# DNS consistency: every env-referenced host resolves to a rendered Service
missing = sorted(h for h in env_hosts if h and h not in services)
if missing:
    print("CHART-GATE FAIL: env wiring references hosts no rendered Service owns: %s" % ", ".join(missing))
    ok = False
else:
    print("dns consistency: %d env-referenced hosts all resolve to rendered Services" % len(env_hosts))

# Isolation posture (the twenty-second pass's contract): every workload pod
# names a rendered ServiceAccount with token automount off, and every chart
# default-denies both ways. Collected first, validated after — a single-pass
# check raced the glob's file order.
for chart, workload, sa, automount, service_links in pods:
    if sa in (None, ""):
        print("CHART-GATE FAIL: %s/%s pod has no serviceAccountName" % (chart, workload))
        ok = False
    elif sa not in accounts:
        print("CHART-GATE FAIL: %s/%s references ServiceAccount %s that no render owns" % (chart, workload, sa))
        ok = False
    if automount is not False:
        print("CHART-GATE FAIL: %s/%s does not disable token automount at the pod level" % (chart, workload))
        ok = False
    if service_links is not False:
        # the EnableServiceLinks collision (found live on the kind cluster): any
        # Service whose name prefixes an env placeholder injects <NAME>_PORT as a
        # tcp:// URL and every such boot dies
        print("CHART-GATE FAIL: %s/%s does not disable k8s service-link env injection" % (chart, workload))
        ok = False
for chart in sorted(os.path.basename(p)[:-5] for p in glob.glob(render_dir + "/*.yaml")):
    if budgets.get(chart, 0) == 0:
        # the disruption budget is the twenty-third pass's contract: a voluntary
        # eviction (node drain) must meet a registered budget even at replicaCount 1
        print("CHART-GATE FAIL: %s renders no PodDisruptionBudget" % chart)
        ok = False
    if chart not in policies:
        print("CHART-GATE FAIL: %s renders no NetworkPolicy at all" % chart)
        ok = False
    elif not {"Ingress", "Egress"} <= policies[chart]:
        print("CHART-GATE FAIL: %s carries no default-deny (Ingress+Egress) NetworkPolicy" % chart)
        ok = False
if ok:
    print("isolation posture: %d workload pods on named ServiceAccounts, token automount off; %d charts default-deny both ways; %d disruption budgets registered"
          % (len(pods), len(policies), sum(budgets.values())))

sys.exit(0 if ok else 1)
PY
[ "$status" -eq 0 ] || exit 1
echo "chart gate: CLEAN"
