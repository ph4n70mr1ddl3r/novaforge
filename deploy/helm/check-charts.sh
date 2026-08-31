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

# 3) DNS consistency: every env-referenced host resolves to a rendered Service
python3 - "$render_dir" <<'PY' || status=1
import glob, re, sys, yaml

render_dir = sys.argv[1]
services = set()
env_hosts = set()
# A bare value is a HOST only when its env NAME says so — anything else
# (KC_DB: postgres, KC_DB_USERNAME: keycloak) is a vendor string or credential,
# and treating it as a host is how false positives drown the signal.
HOST_ENV = re.compile(r"^(NOVAFORGE_[A-Z0-9_]*HOST|NOVAFORGE_KAFKA|NOVAFORGE_POSTGRES_HOST)$")
for path in glob.glob(render_dir + "/*.yaml"):
    text = open(path).read()
    for doc in text.split("\n---"):
        if not doc.strip():
            continue
        try:
            obj = yaml.safe_load(doc)
        except Exception as e:
            print("CHART-GATE FAIL: unparseable render %s: %s" % (path, e))
            sys.exit(1)
        if not isinstance(obj, dict):
            continue
        if obj.get("kind") == "Service" and isinstance(obj.get("metadata"), dict):
            services.add(obj["metadata"].get("name"))
        # walk pod specs for env pairs — name-bounded, no free-text value guessing
        def walk(node):
            if isinstance(node, dict):
                if "name" in node and "value" in node and isinstance(node.get("value"), str):
                    yield node["name"], node["value"]
                for v in node.values():
                    yield from walk(v)
            elif isinstance(node, list):
                for v in node:
                    yield from walk(v)
        for env_name, value in walk(obj):
            m = re.match(r"https?://([a-z0-9.-]+)(?::\d+)?", value)
            if m:
                env_hosts.add(m.group(1))
            elif HOST_ENV.match(env_name):
                env_hosts.add(value.strip().split(":")[0])

missing = sorted(h for h in env_hosts if h and h not in services)
if missing:
    print("CHART-GATE FAIL: env wiring references hosts no rendered Service owns: %s" % ", ".join(missing))
    sys.exit(1)
print("dns consistency: %d env-referenced hosts all resolve to rendered Services" % len(env_hosts))
PY
[ "$status" -eq 0 ] || exit 1
echo "chart gate: CLEAN"
