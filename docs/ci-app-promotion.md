# Headless Suite Runs + CI Promotion Wiring (PHASE-8 §5)

The harness APIs are public and builder-gated — the builder UI is a client of the
same API, never a prerequisite (ADR-010's third activation).

## The API surface

| Call | Purpose |
|------|---------|
| `POST /api/v1/metadata/apps/{appId}/suite-runs` | App-wide headless run (every suite, or `{"suites": [...]}`); returns `{batch, green, runs[]}` |
| `GET  /api/v1/metadata/apps/{appId}/suite-runs` | Recorded run artifacts — version-bound by content hash |
| `POST /api/v1/metadata/suites/{suiteRowId}/runs` | Single-suite run by suite row id |
| `GET  /api/v1/metadata/suites/{suiteRowId}/runs` | That suite's recorded runs |
| `POST /api/v1/metadata/apps/{appId}/environments/{env}/promote` | The gated hop (`{version}`; `{override, reason}` is admin-only) |

Every run — interactive or headless — records an artifact whose **content hash** is
the candidate bundle's sha256; publish records the same hash on the version row, so
the promotion gate's "green run against exactly V" is a mechanical match.

## Pipeline authentication (the §5 pin)

Headless callers use a JWT from a Keycloak service-account client
(`novaforge-pipeline` in the deployed realm — client-credentials grant) granted
`builder`. The client's service account carries the dev workspace's `tenant_id`
attribute. This is realm configuration, not platform metadata — API-client
*definitions* stay deferred with demand (PHASE-6 §1).

```bash
TOKEN=$(curl -sf -X POST "$KEYCLOAK/realms/novaforge/protocol/openid-connect/token" \
  -d 'grant_type=client_credentials&client_id=novaforge-pipeline&client_secret=…' \
  | jq -r .access_token)

curl -sf -X POST "$MD/api/v1/metadata/apps/$APP/suite-runs" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' | jq .green

curl -sf -X POST "$MD/api/v1/metadata/apps/$APP/environments/staging/promote" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"version\": $VERSION}"
```

## The reusable workflow

`.github/workflows/app-suite-gate.yml` ships the green-run → promote pattern as a
`workflow_call` reusable workflow (see its header for the contract). The platform
dogfoods it: this repo's PR pipeline runs the platform's own suites through
`./mvnw verify` — the same harness, the same scratch tenants, the same single write
path (ADR-010 #3).

The platform does not run customer CI in v1 — the pattern is code + documentation;
app teams point their own runners at their own metadata service.
