#!/usr/bin/env python3
"""Live suite-run driver: imports an app artifact (suites inline), runs its suites.

The Phase 4/6/7 live exit legs (PHASE-4 T12, PHASE-6 T10, PHASE-7 T9): the authored
acceptance corpora executed through the real stack — gateway-free direct service
calls, the same APIs the builder UI rides. Requires the compose infra + the six
suite-path services (metadata 8081, runtime 8083, script 8084, workflow 8086,
reporting 8089, integration 8090) and Keycloak on 8082.

Usage: live-run-suites.py <app.json> <suite-json>... (or: --list <appId>)
"""
import json
import sys
import time
import urllib.request
import urllib.error

AUTH = "http://localhost:8082/realms/novaforge/protocol/openid-connect/token"
METADATA = "http://localhost:8081/api/v1/metadata"


def token():
    body = ("grant_type=password&client_id=novaforge-api&username=demo&password=demo"
            ).encode()
    req = urllib.request.Request(AUTH, data=body,
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.load(response)["access_token"]


def call(method, url, payload=None, auth=None):
    data = json.dumps(payload).encode() if payload is not None else None
    headers = {"Content-Type": "application/json"}
    if auth:
        headers["Authorization"] = "Bearer " + auth
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=300) as response:
            raw = response.read().decode()
            return response.status, json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as error:
        raw = error.read().decode()
        try:
            return error.code, json.loads(raw)
        except json.JSONDecodeError:
            return error.code, {"raw": raw}


def main():
    if sys.argv[1] == "--delete":
        status, _ = call("DELETE", f"{METADATA}/apps/{sys.argv[2]}", auth=token())
        print(f"delete {sys.argv[2]}: {status}")
        return
    if sys.argv[1] == "--list":
        status, apps = call("GET", f"{METADATA}/apps", auth=token())
        for app in apps:
            print(app["id"], app["apiName"],
                  "suites:", [s["apiName"] for s in app.get("testSuites", [])])
        return

    app_path = sys.argv[1]
    app = json.load(open(app_path))
    app["testSuites"] = [json.load(open(path)) for path in sys.argv[2:]]

    auth = token()
    # a same-apiName draft from an earlier attempt is replaced (idempotent re-import)
    status, existing = call("GET", f"{METADATA}/apps", auth=auth)
    for other in existing:
        if other["apiName"] == app["apiName"]:
            print(f"replacing stale draft {other['id']} ({other['apiName']})")
            call("DELETE", f"{METADATA}/apps/{other['id']}", auth=auth)

    status, created = call("POST", f"{METADATA}/apps", app, auth=auth)
    if status >= 300:
        print("import failed:", status, json.dumps(created)[:600])
        sys.exit(1)
    app_id = created["id"]
    print(f"imported {app['apiName']} as {app_id} "
          f"(suites: {[s['apiName'] for s in app['testSuites']]})")

    for suite in app["testSuites"]:
        name = suite["apiName"]
        started = time.time()
        status, artifact = call(
            "POST", f"{METADATA}/apps/{app_id}/test-suites/{name}/run", {}, auth=auth)
        elapsed = time.time() - started
        if status >= 300:
            print(f"  {name}: RUN FAILED http {status} {json.dumps(artifact)[:400]}")
            continue
        cases = artifact.get("cases", [])
        verdict = "GREEN" if artifact.get("green") else "RED"
        print(f"  {name}: {verdict} in {elapsed:.1f}s "
              f"(run {artifact.get('runId', '')[:8]}, scratch {artifact.get('tenantId', '')[:8]})")
        for case in cases:
            mark = "ok " if case.get("passed") else "FAIL"
            print(f"    [{mark}] {case.get('name')}")
            for failure in case.get("failures", []):
                print(f"           - {failure[:220]}")


if __name__ == "__main__":
    main()
