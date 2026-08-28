#!/usr/bin/env python3
"""PHASE-8 §8 pen-pass live probes (the runbook's method item 2): adversarial
checks against the RUNNING stack — scratch tenants, synthetic actors, no
production data. Complements the suite baseline (the suites encode intended
verdicts; these probes attack the deployed surfaces end to end).

Usage: python3 docs/loadtests/pen-pass-probes.py   (stack up; records PASS/FAIL
per probe; the run record lands in docs/runbooks/pen-pass-2026-08-28.md)
"""
import json
import time
import urllib.error
import urllib.request
import uuid

GW = "http://localhost:8080"
AUTH = "http://localhost:8082/realms/novaforge/protocol/openid-connect/token"
RUN = uuid.uuid4().hex[:8]
results = []


def token(user="demo", password="demo", client="novaforge-api", secret=None):
    body = (f"grant_type=password&client_id={client}&username={user}&password={password}"
            if secret is None else
            f"grant_type=client_credentials&client_id={client}&client_secret={secret}")
    req = urllib.request.Request(AUTH, data=body.encode(),
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)["access_token"]


def call(method, url, payload=None, tok=None, headers=None, timeout=60):
    data = json.dumps(payload).encode() if payload is not None else None
    h = {"Content-Type": "application/json"}
    if tok:
        h["Authorization"] = "Bearer " + tok
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode()
            return response.status, json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as error:
        raw = error.read().decode()
        try:
            return error.code, json.loads(raw)
        except json.JSONDecodeError:
            return error.code, {"raw": raw}


def probe(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))


# --- baseline tokens
demo = token()
# 1. unauthenticated API access must be problem+json 401
status, body = call("GET", f"{GW}/api/v1/metadata/apps")
probe("unauthenticated API → 401 problem+json", status == 401 and body.get("title") == "Unauthorized",
      f"status {status}")

# --- adversarial tenant: fresh tenant + admin (the admin API, as platform admin)
status, tenant = call("POST", f"{GW}/api/v1/admin/tenants", {
    "apiName": f"pen{RUN}", "displayName": f"Pen {RUN}",
    "adminUsername": f"pen-admin-{RUN}", "adminEmail": f"pen-{RUN}@example.test",
    "adminPassword": "pen-secret-1"}, tok=demo)
probe("tenant provisioning (fixture)", status == 200, f"tenant {str(tenant.get('tenantId'))[:8]}")
tenant_id = tenant["tenantId"]
attacker = token(f"pen-admin-{RUN}", "pen-secret-1")

# 2. cross-tenant record addressing: demo-tenant rows through the attacker's token
# (PerfHook is published in the demo tenant with a populated PerfDoc table)
ENTITY = "PerfDoc"
status, rows = call("GET", f"{GW}/api/v1/runtime/{ENTITY}?page=%7B%22size%22%3A1%7D", tok=attacker)
# 4001 UNAUTHORIZED / 4004 NOT_FOUND / empty — every shape is isolation
isolated = status == 404 or (status == 200 and rows.get("total") == 0) or (
    status >= 400 and rows.get("code") in ("4004", "4000", "4001"))
probe("cross-tenant list → isolated (RLS fail-closed)", isolated,
      f"status {status} code {rows.get('code')} total {rows.get('total')}")
_, demo_rows = call("GET", f"{GW}/api/v1/runtime/{ENTITY}?page=%7B%22size%22%3A1%7D", tok=demo)
if demo_rows.get("rows"):
    rid = demo_rows["rows"][0]["id"]
    status, body = call("GET", f"{GW}/api/v1/runtime/{ENTITY}/{rid}", tok=attacker)
    probe("cross-tenant record id → not reachable (no existence leak)",
          status == 404 or (status >= 400 and body.get("code") in ("4004", "4001")),
          f"status {status} code {body.get('code')}")
    status, body = call("PATCH", f"{GW}/api/v1/runtime/{ENTITY}/{rid}",
                        {"version": 1, "name": "pwned"}, tok=attacker)
    probe("cross-tenant record write → denied", status >= 400 and status != 500,
          f"status {status} code {body.get('code')}")
    status, body = call("DELETE", f"{GW}/api/v1/runtime/{ENTITY}/{rid}?version=1", tok=attacker)
    probe("cross-tenant record delete → denied", status >= 400 and status != 500,
          f"status {status} code {body.get('code')}")

# 3. the attacker (admin of their tenant) cannot touch platform-admin surfaces
status, _ = call("GET", f"{GW}/api/v1/admin/tenants/{tenant_id}", tok=attacker)
probe("tenant admin ≠ platform admin (admin surface 403)", status == 403, f"status {status}")
status, _ = call("GET", f"{GW}/api/v1/audit/records/00000000-0000-4000-8000-000000000000", tok=attacker)
probe("audit trail is platform-admin gated", status in (403, 404), f"status {status}")

# 4. HMAC inbound webhook — the route shape is /{tenant}/{entity}/{hookId}
now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
DEMO_TENANT = "11111111-1111-4111-8111-111111111111"
route = f"{GW}/api/v1/webhooks/inbound/{DEMO_TENANT}/Payment/paymentsFeed"
status, body = call("POST", route, {"x": "1"},
                    headers={"X-NovaForge-Timestamp": "2020-01-01T00:00:00Z",
                             "X-NovaForge-Signature": "deadbeef"})
probe("inbound webhook: stale timestamp rejects", status == 401 and body.get("code") == "4012",
      f"status {status} code {body.get('code')}")
status, body = call("POST", route, {"x": "1"},
                    headers={"X-NovaForge-Timestamp": now,
                             "X-NovaForge-Signature": "deadbeef"})
probe("inbound webhook: mangled signature rejects", status == 401 and body.get("code") == "4012",
      f"status {status} code {body.get('code')}")
status, body = call("POST", f"{GW}/api/v1/webhooks/inbound/{DEMO_TENANT}/Payment/does-not-exist",
                    {"x": "1"},
                    headers={"X-NovaForge-Timestamp": now,
                             "X-NovaForge-Signature": "deadbeef"})
probe("inbound webhook: unknown hook id rejected (404 vs 401 recorded as a finding)",
      status in (401, 404), f"status {status} code {body.get('code')}")


# 6. tampered promotion artifact: export the zip, flip a byte, import must reject
import base64
import urllib.request as _u
status, apps = call("GET", f"{GW}/api/v1/metadata/apps", tok=demo)
erp = next((a for a in apps if a["apiName"] == "Erp"), None)
if erp:
    versions = call("GET", f"{GW}/api/v1/metadata/apps/{erp['id']}/versions", tok=demo)[1]
    latest = max(v.get("version", 0) for v in (versions if isinstance(versions, list) else [])) if versions else 0
    if latest:
        req = _u.Request(f"{GW}/api/v1/metadata/apps/{erp['id']}/versions/{latest}/artifact",
                         headers={"Authorization": "Bearer " + demo})
        with _u.urlopen(req, timeout=60) as response:
            art = response.read()
        tampered = bytearray(art)
        tampered[len(tampered) // 2] ^= 0x01
        status2, body2 = call("POST", f"{GW}/api/v1/metadata/artifacts/import",
                              {"apiName": f"Tampered{RUN}",
                               "zipBase64": base64.b64encode(bytes(tampered)).decode()},
                              tok=demo)
        probe("tampered artifact import rejected", status2 >= 400 and status2 != 500,
              f"status {status2} code {body2.get('code')}")
        # and the pristine artifact imports clean (the control leg)
        status3, _ = call("POST", f"{GW}/api/v1/metadata/artifacts/import",
                          {"apiName": f"Control{RUN}",
                           "zipBase64": base64.b64encode(art).decode()},
                          tok=demo)
        probe("pristine artifact imports (the control leg)", status3 == 200, f"status {status3}")

# 5 (last, deliberately): anonymous hammering of the webhook prefix — the limiter
# window must not cover the HMAC legs above
# 5. anonymous hammering of the webhook prefix: rate limiter engages
limited = sum(1 for i in range(150) if call("POST", f"{GW}/api/v1/webhooks/inbound/nope",
                                            {"i": i}, headers={"X-NovaForge-Timestamp": now,
                                                               "X-NovaForge-Signature": "x"},
                                            timeout=15)[0] == 429)
probe("webhook prefix rate-limits under sustained anonymous load", limited > 0, f"{limited}/150 got 429")

# 7. the service-client secret must not grant user surfaces: it is internal-only —
#    the gateway never routes /api/v1/hooks/** (probe: the public route 404s/401s)
status, _ = call("POST", f"{GW}/api/v1/hooks/resume", {}, tok=demo)
probe("internal hook surfaces unreachable through the gateway", status in (401, 403, 404), f"status {status}")

print()
failed = [name for name, ok, _ in results if not ok]
print(f"{len(results) - len(failed)}/{len(results)} probes PASS")
if failed:
    print("FAILED:", *failed, sep="\n  - ")
raise SystemExit(1 if failed else 0)
