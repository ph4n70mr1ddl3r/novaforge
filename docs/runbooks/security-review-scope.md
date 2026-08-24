# Security Review + Pen-Test Scope (PHASE-8 §8)

> The pinned scope (§8): findings triaged with the Phase 7 gap-log discipline —
> `accept-as-platform-feature | backlog | wontfix-with-workaround`, criticals fixed
> in-phase. This checklist is the review's contract; the execution record lands in
> the ledger when the live pass runs.

## Scope matrix

| Surface | Checks | Owning mechanism |
|---------|--------|------------------|
| Authz matrix end-to-end | object CRUD, field visible/readonly/hidden, record sharing (owner/hierarchy/criteria) — every branch verified as an adversarial tenant user, not just positive paths | RoleMatrix + SharingGate suites (`RecordApiTests`, `SharingTests`, `ReportAggregateTests`) re-run against a hostile fixture set; probe for matrix bypass via batch, inline children, and integration principals |
| RLS under adversarial tenants | every storage query stays tenant-scoped: attempts to address another tenant's rows through record ids, query filters, roll-up paths, audit reads, reporting aggregates | RLS fixtures (`test-support`) + a cross-tenant probe suite; the fail-closed policy is the contract |
| Script sandbox escape | host access (classes, I/O, reflection, threads), resource caps (CPU watchdog, heap tripwire, wall clock), `$data.query` authorization relay, `$http` gated on the connector sandbox only | `ScriptSandboxTests` + `ScriptApiTests`; escape attempts: `Java.type`, `process`, thread spawn, prototype pollution into host objects |
| HMAC webhook endpoints | inbound: wrong secret, stale timestamp, mangled/replayed signature, cross-tenant hook ids; outbound: signature validity, filter escape (expressions over the envelope only), retry-to-DLQ exhaustion | `IntegrationWebhookTests` + `WebhookRateLimitFilterTest` |
| Gateway rate limits on public routes | the anonymous webhook prefix under sustained load; limiter fail-open bounded by HMAC; authenticated routes not starved by anonymous traffic | `WebhookRateLimitFilterTest` + a load profile |
| Promotion override abuse | builder cannot promote to prod, cannot override (admin-only, reason-required, audited); tampered artifacts (hash, signature, zip path escapes) rejected; rollback acknowledgment cannot be skipped | `LifecycleTests` (§4 item 3, §2 artifact legs) |
| Secrets store access | credential references only in metadata (never material); rotation keeps two active windows; DLQ payloads never embed secrets; env var/service-account secret hygiene | `IntegrationWebhookTests` rotation legs + artifact export review (credential refs listed, material absent) |

## Method

1. Re-run the platform's own suites as the baseline (they encode the intended
   verdicts — a finding often looks like a suite that passes when it shouldn't).
2. Author adversarial probes per row above as harness suites where expressible
   (ADR-010: the suites are the acceptance contract) — scratch tenants, synthetic
   actors, no production data.
3. Manual review legs: dependency CVE scan, JWT claim handling (`tenant_id` /
   `platform_roles` trust boundary), gateway route table vs service network policy.
4. Findings → the gap log with severity; criticals fixed and re-probed in-phase.

## Out of scope (v1, pinned)

Physical security, social engineering, Keycloak itself (deployed software — its CVE
feed governs), marketplace third-party review (post-1.0 program, §11 Q2).
