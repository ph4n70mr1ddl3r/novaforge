#!/usr/bin/env python3
"""Phase 5 report load test (PHASE-5 §12): validates the ARCHITECTURE.md §9 row —
report (1M rows, grouped aggregate over promoted fields) p95 < 2 s — through the full
journey (gateway → Reporting Service → Data Runtime aggregate pipeline), with the
result cache cold and warm. Both must pass: the cache may not be load-bearing.

Usage:
    python3 docs/loadtests/report-perf.py [--iterations 100] [--report perfAging]

Prerequisites: compose stack up (Keycloak/Postgres/Redis), metadata-service +
data-runtime + gateway + reporting-service running, the ArDesk app published, and
the 1M-row fixture seeded into rec_perf_invoice (see docs/loadtests/
results-2026-08-23-report-perf.md for the seeding transcript).

The cold leg deletes the report's Redis result keys between runs (the cache is a
latency tool, never an authorization boundary — row filters re-evaluate per actor on
every miss); the warm leg repeats the same run so every iteration after the first
serves from cache.
"""
import argparse
import json
import statistics
import sys
import time
import urllib.request

GATEWAY = "http://localhost:8080"
REDIS = "novaforge-redis"
REPORT = {"app": "ArDesk", "params": {}}


def token():
    request = urllib.request.Request(
        "http://localhost:8082/realms/novaforge/protocol/openid-connect/token",
        data=b"grant_type=password&client_id=novaforge-api&username=demo&password=demo",
        method="POST")
    with urllib.request.urlopen(request) as response:
        return json.load(response)["access_token"]


def run_report(bearer, report_id):
    request = urllib.request.Request(
        f"{GATEWAY}/api/v1/reports/{report_id}/run",
        data=json.dumps(REPORT).encode(), method="POST",
        headers={"Authorization": f"Bearer {bearer}",
                 "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def flush_cache():
    """Deletes the reporting service's result keys (cold leg)."""
    import subprocess
    subprocess.run(
        ["podman", "exec", REDIS, "redis-cli", "--scan", "--pattern",
         "novaforge:reporting:results:*"],
        capture_output=True, check=True)


def flush_cache_keys():
    import subprocess
    scan = subprocess.run(
        ["podman", "exec", REDIS, "redis-cli", "--scan", "--pattern",
         "novaforge:reporting:results:*"],
        capture_output=True, text=True, check=True)
    keys = [k for k in scan.stdout.split() if k]
    for key in keys:
        subprocess.run(["podman", "exec", REDIS, "redis-cli", "DEL", key],
                       capture_output=True, check=True)
    return len(keys)


def percentile(samples, fraction):
    ordered = sorted(samples)
    index = min(len(ordered) - 1, int(round(fraction * (len(ordered) - 1))))
    return ordered[index] * 1000.0


def leg(bearer, report_id, iterations, cold):
    samples = []
    for i in range(iterations):
        if cold:
            flush_cache_keys()
        start = time.perf_counter()
        result = run_report(bearer, report_id)
        samples.append(time.perf_counter() - start)
        if i == 0:
            rows = len(result.get("rows", []))
    return samples, rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--iterations", type=int, default=100)
    parser.add_argument("--report", default="perfAging")
    args = parser.parse_args()

    bearer = token()
    print(f"warming one run to confirm the report serves…")
    first = run_report(bearer, args.report)
    rows = len(first.get("rows", []))
    totals = first.get("totals", {})
    print(f"  rows={rows} totals={totals}")

    cold_samples, _ = leg(bearer, args.report, args.iterations, cold=True)
    warm_samples, _ = leg(bearer, args.report, args.iterations, cold=False)

    cold_p95, warm_p95 = (percentile(s, 0.95) for s in (cold_samples, warm_samples))
    cold_p50, warm_p50 = (percentile(s, 0.5) for s in (cold_samples, warm_samples))
    print(f"cache cold p50 {cold_p50:8.1f} ms | p95 {cold_p95:8.1f} ms "
          f"(target < 2000 ms) {'PASS' if cold_p95 < 2000 else 'FAIL'}")
    print(f"cache warm p50 {warm_p50:8.1f} ms | p95 {warm_p95:8.1f} ms "
          f"(target < 2000 ms) {'PASS' if warm_p95 < 2000 else 'FAIL'}")
    return 0 if (cold_p95 < 2000 and warm_p95 < 2000) else 1


if __name__ == "__main__":
    sys.exit(main())
