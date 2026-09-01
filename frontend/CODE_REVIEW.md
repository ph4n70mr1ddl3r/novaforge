
## Twenty-Seventh Pass — 2026-09-01 (the recorded remainder empties: every pass-26 leftover pinned, the "wart" was a stale jar)

### The remainder pins (7 new tests; backend 530, frontend 205)

Every behavior the twenty-sixth pass recorded as deliberately unpinned now has a
test:

- **Materializer per-shape failure isolation**
  (`MaterializerTests.brokenShapeIsolatesAndRetries`): a foreign table occupying
  a projection name stands in for "one app's bad DDL" — `applyAll` over a broken
  and a healthy app throws nothing, the sibling's projection materializes fully
  (table + trigger + RLS), the broken shape stays skipped, and once the
  obstruction is gone the next pass lands the projection (the idempotent-retry
  contract).
- **Runaway-hook bounds** (`HookStepResultTests`): an afterSave flow that
  `createRecord`s its own entity stabilizes at exactly the seed plus one nested
  echo — the hook dispatch's depth bound ends the self-creation cascade (a
  regression to an unbounded recursion explodes the count; a lost echo zeroes
  it) — and a beforeSave flow whose step's `next` points back at itself trips
  the 256-step budget: VALIDATION_FAILED aborts the write, zero rows, never an
  infinite loop.
- **Integration entity-export row ceiling**
  (`ImportResumeTests.entityExportRowCeilingFailsTheJob`): the first export-job
  test in the repo — a 100-row entity behind a 2-row ceiling fails the job on
  the FIRST page (the total is known immediately) with "exceeds the ceiling",
  one lookup, zero uploads: the in-memory assembly never scans or uploads
  unbounded.
- **Integration batch SQL-failure per-item verdict**
  (`IntegrationGuardLegTests.batchRawFailureIsPerItemVerdict`): a batch whose
  second item aborts on a raw RuntimeException (null entity — the unique-race/
  deadlock leg's stand-in) answers 200 with per-item verdicts — item one `ok`
  and committed, item two `error`/5000 "item failed: …" — a rethrow regression
  would convert the partially-committed batch into a request-level 500 with the
  committed verdicts lost.
- **Builder auth byte-twin** (`builder-ui/test/auth-twin.test.ts`): the OIDC
  client ships as byte-identical copies in both shells (only the runtime copy
  had behavioral tests); the canary pins the byte equality so token handling
  cannot silently drift — divergence forces a conscious split into `shared`.
- **Runtime role-suffix mapping** (`shell-guards.test.ts`): held roles map by
  suffix and filter against the app's own permission set — `erp.arClerk`
  (alongside a role the app never defined) drives the arClerk create grant,
  while a role the app does not define maps to nothing (no nav, no grants).

### The "wart" was a stale jar, not a test bug

`resumeClaimFirstVerdictWins` "fails under -Dtest isolation" is corrected: the
failure was an artifact of running module tests WITHOUT `-am`, which resolves
the engine from the last `install`ed jar in `~/.m2` — a snapshot predating
recent engine work. With the engine in the reactor (`-am`, or the full `verify`
CI runs), the test passes in every mode (verified: single-method, whole-class,
and full-module). The lesson stands for module-scoped loops: module tests need
`-am`, or a fresh `install`, never the last-installed snapshot.

### Verification (this pass)

Full `./mvnw verify` green across all 23 modules (530 backend tests); frontend
`pnpm check` + `pnpm -r test` green (205 tests). The chart gate and the golden
journey's green from the twenty-sixth pass are unaffected (no production code
changed beyond the pass-26 landings; this pass added tests only).

### Recorded open after this pass

Empty. Every behavior the coverage sweeps flagged as risky is now pinned, and
the remainder list is gone.
