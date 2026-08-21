#!/usr/bin/env python3
"""Phase 1 load test (PHASE-1 §10): validates the ARCHITECTURE.md §9 read targets
against a running stack — simple read p95 < 50 ms, filtered list p95 < 300 ms, and the
Phase 1 write bar p95 <= 100 ms (50 ms of headroom reserved for Phase 3 hooks).

Usage:
    python3 docs/loadtests/loadtest.py [--reads 200] [--lists 200] [--writes 200]

Prerequisites: compose stack up (Keycloak/Postgres/Redis), metadata-service + gateway +
data-runtime running, and an ERP app published (see README "Verified live demo").
"""
import argparse
import json
import statistics
import sys
import time
import urllib.parse
import urllib.request
import uuid

GATEWAY = "http://localhost:8080"
TENANT = "11111111-1111-4111-8111-111111111111"


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
    print("seeding one record for point reads…")
    seed = call("POST", "/api/v1/runtime/JournalEntry", bearer,
                {"entryDate": "2026-08-21", "status": "DRAFT"})
    record_id = seed["id"]

    read_samples, list_samples, write_samples = [], [], []
    for _ in range(args.reads):
        start = time.perf_counter()
        call("GET", f"/api/v1/runtime/JournalEntry/{record_id}", bearer)
        read_samples.append(time.perf_counter() - start)

    encoded = urllib.parse.quote('{"field":"status","op":"eq","value":"DRAFT"}')
    for _ in range(args.lists):
        start = time.perf_counter()
        call("GET", f"/api/v1/runtime/JournalEntry?filter={encoded}", bearer)
        list_samples.append(time.perf_counter() - start)

    for _ in range(args.writes):
        start = time.perf_counter()
        call("POST", "/api/v1/runtime/JournalEntry", bearer,
             {"entryDate": "2026-08-21", "status": "DRAFT"})
        write_samples.append(time.perf_counter() - start)

    read_p95, list_p95, write_p95 = (percentile(s, 0.95)
                                     for s in (read_samples, list_samples, write_samples))
    print(f"point read   p50 {percentile(read_samples, 0.5):7.1f} ms | p95 {read_p95:7.1f} ms "
          f"(target < 50 ms)   {'PASS' if read_p95 < 50 else 'FAIL'}")
    print(f"filtered list p50 {percentile(list_samples, 0.5):7.1f} ms | p95 {list_p95:7.1f} ms "
          f"(target < 300 ms)  {'PASS' if list_p95 < 300 else 'FAIL'}")
    print(f"record write  p50 {percentile(write_samples, 0.5):7.1f} ms | p95 {write_p95:7.1f} ms "
          f"(bar <= 100 ms)    {'PASS' if write_p95 <= 100 else 'FAIL'}")
    return 0 if (read_p95 < 50 and list_p95 < 300 and write_p95 <= 100) else 1


if __name__ == "__main__":
    sys.exit(main())
