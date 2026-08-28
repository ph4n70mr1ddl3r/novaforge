#!/usr/bin/env python3
"""Phase 3 §11 / Phase 8 §8 write-path load test: the ARCHITECTURE.md §9 rows
measured at the 1M-row tenant dataset with exactly one synchronous beforeSave hook
in the write chain:

    simple record read (cache warm)          p95 <  50 ms
    filtered list, promoted/indexed field    p95 < 300 ms
    record write with 1 sync hook            p95 < 150 ms   (PHASE-3 §11 — this file's
                                                            reason to exist; Phase 1's
                                                            bar was <= 100 ms hookless)

Usage:
    python3 docs/loadtests/hook-perf.py [--reads 200] [--lists 200] [--writes 200]

Prerequisites: compose stack up (Keycloak/Postgres/Redis/Kafka), metadata-service +
gateway + data-runtime running, the PerfHook app published into the demo tenant, and
the 1M-row PerfDoc fixture seeded (see docs/loadtests/results-2026-08-28-hook-perf.md
for the seeding transcript). The write chain: required-field validation → record-scope
validation rule (amount >= 0) → beforeSave setField hook (upper(name)) → optimistic
persist → projection trigger → transactional outbox append.
"""
import argparse
import json
import sys
import time
import urllib.parse
import urllib.request

GATEWAY = "http://localhost:8080"
ENTITY = "PerfDoc"


def token():
    request = urllib.request.Request(
        "http://localhost:8082/realms/novaforge/protocol/openid-connect/token",
        data=b"grant_type=password&client_id=novaforge-api&username=demo&password=demo",
        method="POST")
    with urllib.request.urlopen(request) as response:
        return json.load(response)["access_token"]


def call(method, path, bearer, body=None):
    request = urllib.request.Request(
        GATEWAY + path,
        data=json.dumps(body).encode() if body is not None else None,
        method=method,
        headers={"Authorization": f"Bearer {bearer}", "Content-Type": "application/json"})
    with urllib.request.urlopen(request) as response:
        return json.load(response)


def percentile(samples, fraction):
    ordered = sorted(samples)
    index = min(len(ordered) - 1, int(round(fraction * (len(ordered) - 1))))
    return ordered[index] * 1000.0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--reads", type=int, default=200)
    parser.add_argument("--lists", type=int, default=200)
    parser.add_argument("--writes", type=int, default=200)
    args = parser.parse_args()

    bearer = token()
    print(f"sampleing seeded ids via one list page over the 1M-row fixture…")
    encoded = urllib.parse.quote('{"field":"status","op":"eq","value":"POSTED"}')
    page = call("GET", f"/api/v1/runtime/{ENTITY}?filter={encoded}&size=200", bearer)
    ids = [row["id"] for row in page.get("rows", page if isinstance(page, list) else [])]
    if not ids:  # shape tolerance: some runtimes return {records:[…]}
        ids = [row["id"] for row in page.get("records", [])]
    if not ids:
        print("could not sample ids from the list API:", json.dumps(page)[:300])
        return 1
    print(f"  {len(ids)} ids sampled (list total: {page.get('total', '?')})")

    read_samples, list_samples, write_samples = [], [], []
    for i in range(args.reads):
        target = ids[i % len(ids)]
        start = time.perf_counter()
        call("GET", f"/api/v1/runtime/{ENTITY}/{target}", bearer)
        read_samples.append(time.perf_counter() - start)

    for _ in range(args.lists):
        start = time.perf_counter()
        call("GET", f"/api/v1/runtime/{ENTITY}?filter={encoded}", bearer)
        list_samples.append(time.perf_counter() - start)

    stamp = time.time_ns()
    for i in range(args.writes):
        start = time.perf_counter()
        call("POST", f"/api/v1/runtime/{ENTITY}", bearer, {
            "name": f"load-{stamp}-{i}",
            "status": "POSTED" if i % 2 == 0 else "DRAFT",
            "dueDate": "2026-08-28",
            "amount": "10.50",
        })
        write_samples.append(time.perf_counter() - start)

    read_p95, list_p95, write_p95 = (percentile(s, 0.95)
                                     for s in (read_samples, list_samples, write_samples))
    print(f"point read   p50 {percentile(read_samples, 0.5):7.1f} ms | p95 {read_p95:7.1f} ms "
          f"(target < 50 ms)   {'PASS' if read_p95 < 50 else 'FAIL'}")
    print(f"filtered list p50 {percentile(list_samples, 0.5):7.1f} ms | p95 {list_p95:7.1f} ms "
          f"(target < 300 ms)  {'PASS' if list_p95 < 300 else 'FAIL'}")
    print(f"write + hook  p50 {percentile(write_samples, 0.5):7.1f} ms | p95 {write_p95:7.1f} ms "
          f"(target < 150 ms)  {'PASS' if write_p95 < 150 else 'FAIL'}")
    return 0 if (read_p95 < 50 and list_p95 < 300 and write_p95 < 150) else 1


if __name__ == "__main__":
    sys.exit(main())
