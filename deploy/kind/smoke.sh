#!/usr/bin/env bash
# The live-cluster smoke (deploy/kind): the repeatable form of the cold-boot pass
# first exercised by hand on 2026-08-31 (the twenty-fourth review pass) — a cold
# kind-on-Podman cluster, the infra chart, eleven jib-built service images loaded,
# every pod Ready, and the gateway answering 200 on health and 401 on a
# JWKS-gated route through a port-forward.
#
# The chart gate (deploy/helm/check-charts.sh) proves the charts from the render
# alone; this script proves the NEXT thing the render cannot: that the stack
# actually BOOTS and SERVES. Run it whenever the charts, the images, or the
# infra chart's first-boot surface change:
#
#   deploy/kind/smoke.sh                 # full run (cluster reused if present)
#   deploy/kind/smoke.sh --skip-build    # reuse images already in the daemon
#   deploy/kind/smoke.sh --teardown      # ...and delete the cluster afterwards
#
# Every leg fails loudly (the honest-launch contract: "1/1 Running" or a named
# failure with the pod's own log tail — never a silent pass).
set -euo pipefail
cd "$(dirname "$0")/../.."

SKIP_BUILD=0
TEARDOWN=0
for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=1 ;;
    --teardown)   TEARDOWN=1 ;;
    *) echo "unknown arg: $arg (supported: --skip-build, --teardown)" >&2; exit 2 ;;
  esac
done

log()  { printf '\033[1;34m[smoke]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[smoke] FAIL:\033[0m %s\n' "$*" >&2; exit 1; }

# --- preflight: the four tools the pass needs -------------------------------
for tool in kind kubectl helm podman; do
  command -v "$tool" >/dev/null 2>&1 || fail "preflight: '$tool' not on PATH"
done
CLUSTER=novaforge

# --- 1. cluster up (idempotent: a live novaforge cluster is reused) ---------
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  log "cluster '$CLUSTER' already exists — reusing"
else
  log "creating kind cluster '$CLUSTER' (KIND_EXPERIMENTAL_PROVIDER=podman)"
  KIND_EXPERIMENTAL_PROVIDER=podman kind create cluster \
    --config deploy/kind/novaforge-cluster.yaml --wait 180s \
    || fail "kind create cluster failed — see kind's output above"
fi
kubectl config use-context "kind-$CLUSTER" >/dev/null

# --- 2. images: jib into the daemon, then kind load --------------------------
SERVICES=(gateway metadata-service data-runtime audit-service script-engine
          workflow-service scheduler-service notification-service reporting-service
          integration-service file-service)
# data-runtime builds one image from its aggregator root (api module's jar).
DATA_RUNTIME_MODULE=services/data-runtime
if [ "$SKIP_BUILD" -eq 0 ]; then
  log "building service images with jib (this is the slow leg — --skip-build reuses)"
  for svc in "${SERVICES[@]}"; do
    case "$svc" in
      data-runtime) module="$DATA_RUNTIME_MODULE" ;;
      *)            module="services/$svc" ;;
    esac
    ./mvnw -B -ntp -q -pl "$module" jib:dockerBuild \
      || fail "jib:dockerBuild failed for $svc"
  done
fi
for svc in "${SERVICES[@]}"; do
  image="ghcr.io/novaforge/novaforge-${svc}:0.1.0-SNAPSHOT"
  podman image exists "$image" || fail "image $image absent from the daemon (run without --skip-build)"
  kind load docker-image --name "$CLUSTER" "$image" \
    || fail "kind load failed for $image"
done
log "images built/verified and loaded into kind"

# --- 3. infra chart first, and waited out ------------------------------------
helm upgrade --install novaforge-infra deploy/helm/novaforge-infra \
  --namespace novaforge --create-namespace --wait --timeout 10m \
  || fail "novaforge-infra install failed (a first-boot defect — see the pod events)"
kubectl -n novaforge wait --for=condition=Ready pod --all --timeout=10m \
  || { kubectl -n novaforge get pods; \
       fail "infra pods not Ready — 'kubectl -n novaforge get pods' above names them"; }
log "novaforge-infra Ready (postgres, kafka, redis, keycloak, tempo, loki, minio, clamav)"

# --- 4. the eleven service charts via the umbrella ---------------------------
helm dependency update deploy/helm/novaforge >/dev/null \
  || fail "umbrella dependency update failed"
helm upgrade --install novaforge deploy/helm/novaforge \
  --namespace novaforge --wait --timeout 15m \
  || fail "novaforge umbrella install failed"
kubectl -n novaforge wait --for=condition=Ready pod --all --timeout=15m \
  || { kubectl -n novaforge get pods; \
       fail "service pods not Ready — the failing container's tail: $(kubectl -n novaforge get pods | grep -v Running | head -1 | awk '{print $1}' | xargs -r kubectl -n novaforge logs --tail=20 2>/dev/null || true)"; }
log "all service deployments Ready"

# --- 5. the serving proof: gateway health 200, gated route 401 ---------------
PF_PID=""
cleanup() { [ -n "$PF_PID" ] && kill "$PF_PID" 2>/dev/null || true; }
trap cleanup EXIT
kubectl -n novaforge port-forward svc/novaforge-gateway 18080:8080 >/dev/null 2>&1 &
PF_PID=$!
for i in $(seq 1 30); do
  curl -sf -o /dev/null "http://127.0.0.1:18080/actuator/health" && break
  [ "$i" -eq 30 ] && fail "gateway port-forward never became reachable"
  sleep 1
done
health=$(curl -sf "http://127.0.0.1:18080/actuator/health") \
  || fail "gateway health probe failed"
echo "$health" | grep -q '"UP"' || fail "gateway health is not UP: $health"
log "gateway /actuator/health: UP (200)"
code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:18080/api/v1/runtime/Anything")
[ "$code" = "401" ] || fail "gated route answered $code, expected 401 (JWKS gate)"
log "gated route without a token: 401 (the JWKS gate holds through the cluster)"
printf '\033[1;32m[smoke] PASS:\033[0m cluster boots end to end — infra + 11 services Ready, gateway serving\n'

# --- 6. teardown on request ---------------------------------------------------
if [ "$TEARDOWN" -eq 1 ]; then
  log "tearing down (helm deletes, then kind delete)"
  helm delete novaforge -n novaforge || true
  helm delete novaforge-infra -n novaforge || true
  kind delete cluster --name "$CLUSTER" || true
fi
