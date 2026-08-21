# ADR-002: AuthN in Keycloak, AuthZ in the platform DB (single realm + tenant claim)

- **Status:** Accepted (ahead of implementation — docs-only, like ADR-007–010; confirmed by the Phase 0 realm export and the Phase 1 authorization gate, PHASE-1 §7/T9)
- **Date:** 2026-08-21
- **Affects:** ARCHITECTURE.md §2.2/§2.4, §7 (identity-is-deployed); PLAN.md §3 (Identity/Data Runtime rows), P6; PHASE-0 §6/§7/T4 (realm export); PHASE-1 §7; PHASE-2 §9–§10 (tenant onboarding, admin API); resolves PHASE-0 §12 Q1

## Context

Two identity questions were left open at the architecture level:

1. **Where do app-defined roles live?** NovaForge roles are dynamic, app-authored
   metadata (an ERP app invents `accountant`, `ap-clerk`, …), which does not fit
   Keycloak's static realm-role model. Two mechanisms were considered: syncing
   app-defined roles into Keycloak via its Admin API (Keycloak remains the single
   authority, but every publish mutates realm configuration and every check needs
   the sync to have converged), or an authorization module inside the Data Runtime
   backed by the platform DB.
2. **Realm strategy** (PHASE-0 §12 Q1): one realm per tenant (stronger isolation,
   explosive admin surface at our scale) vs a single realm with a tenant claim.

## Decision

1. **Keycloak handles authentication only.** Deployed, stock Keycloak (OIDC/OAuth2,
   JWKS, MFA and SSO federation as realm-configuration capabilities — activated with
   demand); no bespoke identity service module (ARCHITECTURE.md §7).
2. **Authorization resolves in the platform.** An `authorization/` module in the
   Data Runtime enforces the object-level role × entity → CRUD matrix (plus Phase 2
   field security and Phase 4 record rules), read at request time from the
   **platform authorization store** — a dedicated platform schema inside
   `novaforge-data`, cross-tenant by design (no tenant-RLS policy; gated by the role
   matrix and the platform-admin API, never row filters). Roles are never synced to
   Keycloak.
3. **Single realm `novaforge` + tenant claim.** Tenant derives from the token claim
   (`X-Tenant-Id` at the gateway is informational; services derive from the claim
   themselves). Realm-per-tenant is revisited only at true tenant-isolation
   requirements.
4. Platform roles (`admin`, `builder`, `user`) seed as a bootstrap matrix in Phase 1;
   app-defined roles arrive in Phase 2 as versioned `PermissionSet` metadata, with
   user→role assignments as tenant data (PHASE-2 §9). Tenant provisioning
   (`POST /api/v1/admin/tenants`) orchestrates the Keycloak side via its Admin API —
   deployed configuration, not bespoke code (PHASE-2 §10).

## Consequences

- Keycloak stays configuration-only: realm export in compose (PHASE-0 §7), no custom
  providers, upgrade path unaffected by app-role churn.
- Every authorization check is a platform-DB read at request time — cacheable later;
  permission changes emit audit events (the Phase 2 event shapes, durable with the
  Phase 3 spine).
- Fail-closed default policy until grants are authorable (PHASE-1 §12 Q1's decision).
- Tenant isolation rests on the token claim + RLS (ADR-006) + the authorization
  matrix — three layers, none of them Keycloak realm boundaries.
