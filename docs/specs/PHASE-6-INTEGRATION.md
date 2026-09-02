# Phase 6 — Integration Layer: Implementation Specification

> Complete, implementation-driving spec for connectors, webhooks (both directions),
> bulk import/export, and the File Service. Product context: [PLAN.md](../../PLAN.md)
> §5 Phase 6. Service design: ARCHITECTURE.md §2.8 (Integration, File); primitive
> activation per ADR-008; the PHASE-4 §1 spec-driven agreement applies.
>
> | | |
> |---|---|
> | Status | Decided (open questions resolved 2026-08-21) |
> | Date | 2026-08-16 |
> | Owner | Platform team |
> | Estimate | 3–4 weeks (per PLAN.md §5) |
> | Depends on | Phase 3 (`callConnector` grammar, Script `$http` deferral) + Phase 1 (write path/batch, file field type) + Phase 2 (FileUpload stub) |

## 1. Objective & Exit Criteria

Deliver the Phase 6 exit: *sync a bank feed or Stripe transactions via a connector
into a Payments entity* (PLAN.md §5) — plus working inbound/outbound webhooks,
resumable bulk import/export, and the File Service that unblocks PHASE-2 §5's
disabled `FileUpload` stub before the ERP dogfood.

Out of scope: SOAP/DB/file connector types (they join the same frame only on dogfood
demand — PLAN.md §3/§5, ARCHITECTURE.md §2.8); a connector marketplace (P8 concept);
OAuth grants beyond client-credentials (deferred — §13 Q1, resolved); streaming/outbound event connectors and
tenant-facing message-bus topics (PLAN P7's remaining integration item — both
post-1.0 demand work); **API-client definitions** (machine credentials against the
public REST API — the Integrations-branch item of PLAN.md §2 / ARCHITECTURE.md §2.3
that no phase has yet demanded): deferred with demand — v1's API surface
authenticates users by JWT and inbound hooks by HMAC, so no API-client schema lands
here.

## 2. Service & Infrastructure Additions

| Addition | Detail |
|---|---|
| `novaforge-integration-service` | Port 8090; gateway routes `/api/v1/integrations/**` and — the one deliberately **anonymous** route — `/api/v1/webhooks/inbound/**` (HMAC at the service, §6; the gateway's default JWT requirement is lifted for exactly this path prefix). |
| `novaforge-file-service` | Port 8091; gateway route `/api/v1/files/**`; presigned upload/download. |
| Compose | **MinIO** joins the stack (API 9000, console 9001) with a persistent volume. Both new services hold state — delivery log/DLQ and import checkpoints (integration), attachment metadata (file) — and add their own databases on the shared Postgres, the PHASE-4 §2 per-service pattern. |
| `common-core` | `SIGNATURE_INVALID("4012", 401)` for webhook auth failures. |
| Spine contracts (in-producer — no `event-schemas` lib; the PHASE-0 §5.4 charter resolved in place, PHASE-3 §4) | `connector.delivered`, `webhook.dispatched`, `import.progress` contracts, partition keys pinned at landing per PHASE-3 §4 — `import.*` keyed `tenant_id:job_id` (the job is the family's record, keeping per-record ordering; the progress UI rides it, §7), `connector.*`/`webhook.*` tenant-scoped (`tenant_id`). |
| `metadata-model` | `ConnectorDefinition`, `WebhookDefinition` (one schema, both directions — §5), `CredentialDefinition` (references only — §9), `ImportDefinition` (the §7 import mapping — versioned metadata like connectors; import *runs* are tenant data). |

## 3. Connector Framework (REST first)

```json
{ "id": "con_stripe_tx",
  "type": "rest", "baseUrl": "https://api.stripe.com/v1",
  "credential": "cred_stripe",
  "operations": [
    { "name": "listTransactions", "method": "GET", "path": "/balance_transactions",
      "query": { "limit": "${limit}" } },
    { "name": "getPayment", "method": "GET", "path": "/payment_intents/${id}" } ] }
```

- **Mapping engine:** request/response field maps use the shared `${…}` template
  convention (ADR-008); pure transforms are expression-DSL snippets compiled at
  publish; genuinely procedural transforms are scripts (escape hatch, counted in the
  script ratio).
- **Outbound execution:** Resilience4j circuit breaker + bounded retries with
  exponential backoff; every delivery idempotent (dedupe key from
  provider response/event id) with a DLQ after terminal failure — deliveries land
  in audit (ARCHITECTURE.md §2.8).
- Auth set v1: API-key header, HTTP basic, OAuth2 client-credentials (user-context grants deferred — §13 Q1).
- **Builder authoring:** every Integration-branch definition of §2 — connectors,
  webhooks (both directions), credential references, import mappings — is
  builder-authored metadata: guided forms with the same save-time
  schema/reference checking as the Phase 3 rule editors, with delivery logs and
  DLQ replay surfacing beside them (§5–§7). PHASE-7 §1's rule 1 binds
  integrations to the builder; nothing here is API-only.
- Connector runs use the **per-app integration principal** — a distinct principal
  from the engine's per-app *system* principal (PHASE-4 §4), so audit provenance
  separates integration-sourced writes from engine actions. Writes they produce go
  through the Data Runtime single write path (§6), never direct SQL.

## 4. `callConnector` Activates (the last dormant primitive)

- Params: `{ connector, operation, template }` — compiled at publish (operation
  exists, template resolves against the step context).
- **Timeout — pinned:** 10 s. On timeout/failure the step fails: inside a
  `before*` hook the transaction aborts; inside an `after*` hook it retries via the
  spine (the PHASE-3 §2 failure policy, unchanged). No flow suspension — unlike
  `requestApproval`, connector calls are synchronous.
- The Script Engine's `$http` whitelisting turns on **only** for scripts whose
  artifact declares a connector sandbox context (the PHASE-3 §6 deferral): scripts
  may call REST through the same circuit-breaker/credential machinery, never raw
  sockets.

## 5. Webhook Dispatch (platform → outside)

- `WebhookDefinition` metadata: `{ url, events (filter expression over spine
  events), secretRef, enabled }` — versioned, promoted. One schema covers both
  directions via a `direction` discriminator: `outbound` carries `url` + `events`;
  `inbound` carries `entity` + `mapping` (§6) in their place; both carry
  `secretRef`/`enabled`.
- **Signing — pinned:** HMAC-SHA256 over the raw body with a timestamp header
  (`X-NovaForge-Timestamp`), signature in `X-NovaForge-Signature`; a ±5 minute
  window rejects replay. The same scheme protects inbound (§6) — one scheme, two
  directions.
- Retries: exponential backoff to a terminal DLQ; delivery log per attempt
  (status, latency, response code) surfaced in the builder; all deliveries audited.

## 6. Inbound Webhooks (outside → platform)

- Endpoint: `POST /api/v1/webhooks/inbound/{tenant}/{entity}/{hookId}` — anonymous
  at the gateway (§2), authenticated by the same HMAC scheme (§5) at the service;
  failures render `SIGNATURE_INVALID` problem+json.
- **Mapping → records:** the hook's mapping produces create/update payloads applied
  through the Data Runtime write path as the integration principal — validations,
  state machines, and hooks all fire (a webhook is just another writer; the single
  write path is absolute).
- Idempotency: provider event id (or body hash when absent) dedupes; poison
  messages DLQ with the payload preserved for replay from the builder.
- **Rate limiting lands here:** the anonymous route is the gateway's only public
  API path (§2), and it is rate-limited — Redis, enforced at the gateway — from
  its first day. The PHASE-0 §6.1 deferral activates with the route that needs it.

## 7. Bulk Import / Export (async, resumable) + Async Report Export

- **Import:** file lands via presigned upload (§8) → an `ImportDefinition`
  (versioned metadata — `{ entity, mapping, mode: create | upsert, keyFields }`,
  promoted with the app like connectors, §2) → the import *run* (`ImportJob`,
  tenant data) chunk-processes through the batch API (per-item outcomes,
  PHASE-1 §5) → **checkpointed for resume**: a killed run restarts from its last
  checkpoint with per-row idempotency (upsert keys or generated keys recorded), so
  a row is applied exactly once.
- **Export:** entity or report datasets stream asynchronously to the File Service
  in chunks; **this activates PHASE-5 §6's designed handoff** — sync exports over
  the 10k cap return a job link instead of an error once this phase lands.
- Job lifecycle: import runs and entity export jobs are created, inspected, and
  resumed via the Integration Service's operational APIs under
  `/api/v1/integrations/**` (§9's `builder` gate); the >10k *report* export needs no
  separate builder call — it rides PHASE-5 §6's designed handoff, the reporting
  export endpoint itself returning the job link.
- Progress events (`import.progress`) drive the builder progress UI and completion
  notifications — a built-in `job-completed` Notification category joining the v1
  defaults per PHASE-4 §8's growth path (`report-delivery` was the first), delivered
  to the job's initiating user; every job audited with per-item outcomes retained.

## 8. File Service v1

- MinIO/S3 presigned upload/download URLs; the **attachment metadata entity**
  (`fileId, entity, recordId, fileName, contentType, size, checksum(SHA-256),
  virusScan: pending | clean | infected | skipped`).
- The `file` field type gets its upload path (the PHASE-1 §3 pin resolves): values
  are attachment ids; runtime-ui's `FileUpload` stub activates (PHASE-2 §5/§6).
- Optional ClamAV hook is config-gated (§13 Q2, resolved — off locally, on in staging/prod; CI runs one config-on job);
  infected files quarantine (download blocked) and raise an audit event.
- Checksums verified server-side on upload completion; presigned URLs expire
  (pinned: 15 minutes).

## 9. Security

- Route gates on the new routes (the PHASE-4 §13 pattern): `/api/v1/integrations/**`
  = `builder`+ — definition authoring and the operational surfaces (delivery log,
  DLQ replay, import progress) are builder tooling in v1; `/api/v1/files/**` =
  `user`+, attachment access governed by the owning record's authorization —
  presigned URLs are short-lived and attachment-scoped (§8); the inbound-webhook
  prefix stays anonymous by design (§6), HMAC at the service.
- **Credentials never live in metadata:** `CredentialDefinition` holds only a
  reference; the secret material sits in the secrets store, encrypted at rest
  AES-GCM with keys in KMS/Vault (ARCHITECTURE.md §5 item 6 — locally, a
  compose-provided data key). Exports/redactions strip references.
- **Connector egress policy (two layers, both pinned):**
  1. **The authoring door** — save/publish validation rejects a connector
     `baseUrl` targeting internal networks: link-local (169.254.0.0/16, the cloud
     metadata range), RFC1918 private hosts, and the internal host suffixes
     (`localhost`, `.internal`, `.svc`, `.cluster.local`). Literal IPs check
     directly; a DNS name is never resolved at the door (a name that fails to
     resolve is normal at publish time — rebinding through real DNS is the
     execution layer's job). **Loopback literals (`127.0.0.0/8`, `::1`) are exempt
     at the door**: in every supported production topology (the Helm charts,
     default-deny NetworkPolicy) loopback is the connector's own pod — nothing
     else lives there — while §10's harness-provided mock connector (an in-process
     stub the suite runner binds on loopback before the candidate publishes)
     requires exactly that shape; a door that blocked loopback would break every
     offline `callConnector` suite journey. Private/link-local/metadata targets
     stay blocked at the door unconditionally — the harness never rewrites to
     them.
  2. **The execution-time re-check (the durable layer)** — the Integration
     Service re-resolves the request URL's host before every connector dispatch
     and refuses internal-network targets (loopback, link-local, RFC1918,
     any-local) with `VALIDATION_FAILED` problem+json naming the connector — DNS
     rebinding cannot pass a door that checked a different address. One explicit,
     deployment-postured exemption: `novaforge.connector.egress.allow-loopback`
     (default **true** for the local/host-JVM stack, where the suite runner's mock
     connector is loopback-reachable by design; the Helm charts pin it **false** —
     staged and production clusters refuse loopback dispatch, and suite runs are
     a dev-workspace activity that never executes there).
- HMAC secret rotation: two active secrets per hook during rotation windows.
- Audit: dispatches, deliveries, DLQ moves, imports/exports, file uploads
  (metadata, not bytes), quarantine events.

## 10. Test-Harness Growth

- New step op: `postWebhook { hookId, body, headers? }` — the harness signs with
  the scratch tenant's hook secret, so suites exercise the real HMAC path; expect
  `ok` or `error(SIGNATURE_INVALID)` for deliberately-mangled signatures.
- Suites can bind a `ConnectorDefinition` to a mock base URL (harness-provided
  stub server in the scratch environment) — the bank-feed journey runs offline.
  The stub binds loopback (`127.0.0.1`) in the runner's JVM and every connector's
  `baseUrl` is rewritten to it before the candidate publishes — the §9 egress
  door's loopback exemption exists for exactly this rewrite (all-JVM local stack:
  loopback reaches the runner's stub from every service).

## 11. Testing Standards

1. HMAC matrix: valid, wrong secret, stale timestamp, replayed signature →
   `SIGNATURE_INVALID`; rotation with old+new secrets both valid.
2. Retry/DLQ: terminal failure exhausts backoff, lands in DLQ, replay from builder
   succeeds exactly once.
3. `callConnector`: timeout aborts before-hooks, retries after-hooks; mock
   connector journey creates Payments records end-to-end.
4. Import resumability: kill mid-run → resume → per-row exactly-once assertion
   (count + upsert keys).
5. File: checksum mismatch rejected; presigned expiry enforced; ClamAV gate
   (config-on scenario) quarantines an EICAR sample.
6. Webhook-driven writes fire validations/state machines (a webhook cannot smuggle
   a bad record past the write path).

## 12. Task Breakdown

| # | Task | Content | Acceptance criteria |
|---|---|---|---|
| T1 | Integration skeleton + DLQ | Port 8090, routes, resilience defaults, audit wiring | Health behind gateway; DLQ store live |
| T2 | Credential store + secrets | References, AES-GCM at rest, rotation support (§9) | Secrets absent from metadata JSON exports |
| T3 | Connector framework | REST executor, mapping engine, circuit breaker, integration-definition editors (§3) | §11 item 3 mock journey green; connector authorable in the builder |
| T4 | `callConnector` + `$http` sandbox | Primitive activation, timeout policy, script sandbox context (§4) | Before/after failure-policy tests green |
| T5 | Outbound webhooks | Definitions, HMAC signing, retries, delivery log (§5) | §11 items 1–2 green |
| T6 | Inbound webhooks | Anonymous route, HMAC, mapping → write path, poison DLQ (§6) | §11 items 1 + 6 green |
| T7 | File Service + MinIO | Presigned flow, attachment entity, checksum, ClamAV gate (§8) | §11 item 5 green; FileUpload stub active in runtime-ui |
| T8 | Bulk import/export | Jobs, checkpoints, resume, async report export handoff (§7) | §11 item 4 green; >10k report export returns a job link |
| T9 | Harness + mock connector | `postWebhook` op, stub server (§10) | Bank-feed suite green through the runner |
| T10 | Exit review | Walk PLAN §5 exit | Demo: Stripe/bank feed → Payments, visible in reports |

Dependency order: T1 → (T2, T7) → T3 → (T4, T5, T6) → T8 → T9 → T10; T7 can start
at phase start.

## 13. Resolved Questions (decided 2026-08-21, per the recommendations; both were non-blocking scope pins)

- **Q1 — OAuth grant coverage: DECIDED — client-credentials only** in v1;
  authorization-code + refresh for user-context APIs join only when a dogfooded
  connector needs them.
- **Q2 — ClamAV in local compose: DECIDED — config-gated, off locally**; CI runs
  one config-on job so the scanning path stays tested.
