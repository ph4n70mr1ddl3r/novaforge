#!/usr/bin/env python3
"""Deep-offset probe — evaluates the PHASE-1 §12 Q2 keyset-paging trigger.

The §12 Q2 decision: "Keyset paging joins only if the §10 load test shows
deep-offset pain." The 2026-08-21 exit run drove a small dataset (its own note
defers deep paging to "if it appears"), and no later run measured paging depth —
so the trigger stayed unevaluated. This probe closes it: the §10 filtered-list
shape (promoted/indexed filter + sort) driven at increasing OFFSET depths against
the 1M-row fixture, seeded exactly as the 2026-08-23 report-perf run records
(generate_series into rec_records, trigger-maintained projection, ANALYZE).

Bar: the §10 filtered-list target — p95 < 300 ms on promoted/indexed fields —
applied at every depth. Pain = a deep leg breaking the bar; that fires the
keyset-paging revisit. No pain = the trigger evaluates negative and keyset stays
off by decision.

Usage: token via NOVAFORGE_TOKEN (or demo/demo fetch), gateway via
NOVAFORGE_GATEWAY (default http://localhost:8080), entity bound to the perf app.
"""

import json
import os
import statistics
import time
import urllib.parse
import urllib.request

GATEWAY = os.environ.get("NOVAFORGE_GATEWAY", "http://localhost:8080")
ENTITY = os.environ.get("NOVAFORGE_ENTITY", "PerfDoc")
ITERATIONS = int(os.environ.get("NOVAFORGE_ITERATIONS", "25"))


def token():
    if os.environ.get("NOVAFORGE_TOKEN"):
        return os.environ["NOVAFORGE_TOKEN"]
    body = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "novaforge-api",
        "username": "demo", "password": "demo",
    }).encode()
    with urllib.request.urlopen("http://localhost:8082/realms/novaforge/protocol/openid-connect/token",
                                body) as response:
        return json.load(response)["access_token"]


def percentile(samples, fraction):
    ordered = sorted(samples)
    return ordered[min(len(ordered) - 1, int(len(ordered) * fraction))]


def probe_page(bearer, filter_json, sort_json, size, offset):
    params = {"page": json.dumps({"size": size, "offset": offset})}
    if filter_json:
        params["filter"] = filter_json
    if sort_json:
        params["sort"] = sort_json
    url = f"{GATEWAY}/api/v1/runtime/{ENTITY}?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {bearer}"})
    started = time.perf_counter()
    with urllib.request.urlopen(request) as response:
        payload = json.load(response)
    elapsed_ms = (time.perf_counter() - started) * 1000
    rows = len(payload.get("rows", payload.get("records", payload.get("items", []))) or [])
    return elapsed_ms, rows, payload.get("total")


def leg(name, bearer, filter_json, sort_json, size, offset, bar_ms, iterations=ITERATIONS):
    samples, rows_seen, total_seen = [], None, None
    for _ in range(iterations):
        elapsed_ms, rows, total = probe_page(bearer, filter_json, sort_json, size, offset)
        samples.append(elapsed_ms)
        rows_seen, total_seen = rows, total
    p50, p95 = statistics.median(samples), percentile(samples, 0.95)
    verdict = "PASS" if p95 < bar_ms else "**PAIN** (over the bar)"
    print(f"| {name} | {offset:,} | {size} | {p50:.1f} ms | {p95:.1f} ms | {rows_seen} | {verdict} |")
    return {"leg": name, "offset": offset, "size": size, "p50": round(p50, 1),
            "p95": round(p95, 1), "rows": rows_seen, "total": total_seen, "bar": bar_ms}


def main():
    def fresh_token():
        new = token()
        global _last_token
        _last_token = new
        return new

    # the realm's access-token lifespan is short — refresh per leg, or a long
    # deep-offset sweep dies at 401 halfway through (found live)
    bearer = fresh_token()
    posted_filter = json.dumps(
        {"and": [{"field": "status", "op": "eq", "value": "POSTED"}]})
    due_sort = json.dumps([{"field": "dueDate", "dir": "desc"}])

    print("Probing with one warm-up call per leg shape…")
    probe_page(bearer, posted_filter, due_sort, 50, 0)
    probe_page(bearer, None, None, 50, 0)

    results = []
    print("\n| Leg (§10 filtered shape: status=POSTED, sort dueDate desc) | OFFSET | size | p50 | p95 | rows | verdict |")
    print("|---|---|---|---|---|---|---|")
    for offset in (0, 1_000, 10_000, 100_000, 250_000, 400_000):
        results.append(leg("filtered, indexed sort", fresh_token(), posted_filter, due_sort, 50, offset, 300))
    print("\n| Leg (max page size) | OFFSET | size | p50 | p95 | rows | verdict |")
    print("|---|---|---|---|---|---|---|")
    for offset in (0, 100_000, 400_000):
        results.append(leg("filtered, size 200", fresh_token(), posted_filter, due_sort, 200, offset, 300))
    print("\n| Leg (unfiltered, default sort — raw OFFSET cost) | OFFSET | size | p50 | p95 | rows | verdict |")
    print("|---|---|---|---|---|---|---|")
    for offset in (0, 100_000, 500_000, 990_000):
        results.append(leg("unfiltered, default sort", fresh_token(), None, None, 50, offset, 300))

    worst = max(results, key=lambda r: r["p95"])
    print(f"\nWorst p95 anywhere: {worst['p95']} ms "
          f"({worst['leg']}, OFFSET {worst['offset']:,}, size {worst['size']}) — "
          f"bar 300 ms → trigger {'FIRES' if worst['p95'] >= 300 else 'evaluates negative'}")


if __name__ == "__main__":
    main()
