# The ERP Dogfood App (`apps/erp`)

The Phase 7 proving-ground artifact (PHASE-7 §2): a mini-ERP authored **entirely as
metadata** — entities, relationships, state machines, posting flows, roles, sequences,
reports, dashboard, scheduled delivery, integrations — with zero handwritten
application code and exactly one budgeted escape-hatch script (§5, rule 3 — the
budget is exceeded, 1 script of 4 hooks = 25%, under G-2's reviewed exception;
reported per module in change-set review, PHASE-7 §9 item 7).

| File | What it is |
|------|------------|
| `erp-app.json` | The app definition (GL, AR, Inventory, Periods, Settings, Permissions, reports, dashboard, job, integrations, close-checklist workflow) |
| `suites/reconciliation.json` | The §9 item 1/5 exit contract: book → approval → **auto-journal** (`createRecord` from templates, G-1 adopted) → journal approval → POSTED → trial balance nets zero, aging reconciles, SoD |
| `suites/controls.json` | §9 items 2–3: posting immutability (`RECORD_FROZEN`) + period locking (`PERIOD_LOCKED`, incl. §4's soft close — `CLOSING` blocks postings unless `closeJournal`) + reopen |
| `suites/inventoryCosting.json` | §9 item 4: receipt → issue at weighted average, decimal-exact through the rounding chain |
| `suites/bankFeed.json` | PHASE-6 T10 / §5 T8: webhook payment (real HMAC path) → settlement → aging reflects it, decimal-exact |
| `suites/creditAndCurrency.json` | §9 item 6 + the AR/AP rows: credit-note allocation, EUR invoice at the document rate posting in USD book currency (the auto-journal's `totalBook` conversion), dunning mirroring its aging bucket, the AP vendor subledger |
| `GAP-LOG.md` | The binding phase deliverable (§1 rule 2): every gap logged before any workaround, with dispositions — mirrored as `erp-app.json`'s `gapLog` branch (PHASE-8 §3's review surface) |

## Module map (§2)

- **GL** — `Account` (hierarchical lookup), `JournalEntry`/`JournalLine` (balanced
  validation; `DRAFT → SUBMITTED → POSTED` with `POSTED` terminal **and
  `freezeOnTerminal`** — append-only in fact, not convention; gapless `JE-` numbering
  via the field-default sequence reference; `PeriodLock` binds `entryDate` to
  `AccountingPeriod`), posting = the `submitForPosting` flow: branch →
  `requestApproval` (role `accountingManager`, SoD fail-closed) → `transitionState`
  to `POSTED`; rejection publishes `journal.rejected` on the spine.
- **AR** — `Customer`, `Invoice` + lines (`amount = quantity × unitPrice` formula;
  `total` roll-up; `totalBook = total × fxRate` — the document total in book
  currency, what the auto-journal posts; `arAccount`/`revenueAccount` posting-account
  lookups; gapless `INV-` numbering; `DRAFT → SUBMITTED → POSTED`,
  `freezeOnTerminal` binds the journal entry, not the invoice — settlement decrements
  `amountOutstanding` on POSTED invoices), `Payment` (the bank-feed webhook target).
  Posting (§5's shape, G-1 adopted 2026-09-02): branch → `requestApproval` →
  **`createRecord` JournalEntry** (deep-resolved lines: AR debit / revenue credit at
  `totalBook`, `sourceInvoice` linking back) → `transitionState` to `POSTED`; the
  auto-created journal then posts through its own GL approval. Rejection publishes
  `invoice.rejected` and creates nothing.
- **Inventory** — `Item` (roll-up maintained `qtyOnHand`/`inventoryValue` — the
  running weighted average is exactly `inventoryValue / qtyOnHand`), `StockLedger`
  (append-only movements, terminal `POSTED` + `freezeOnTerminal`). The **one budgeted
  script** (`costMovement`, beforeSave) costs issues at the running average and stamps
  receipt values — §5's canonical ADR-008 escape-hatch case.
- **Periods** — `AccountingPeriod` `OPEN → CLOSING → CLOSED` with the audited
  `CLOSED → OPEN` reopen edge (§4); `CLOSING` blocks postings unless the entry
  carries `closeJournal: true` (§4's soft close — the `PeriodLock`
  `restrictedStatus`/`exemptField` binding); `CLOSED` activates the absolute lock;
  nothing is ever un-frozen — corrections inside a reopened period are reversal
  entries. The **`closeChecklist` workflow** (§4) starts when a period enters
  `CLOSING`: parallel close tasks per role (AR clerk, inventory clerk, accounting
  manager) join before the controller's `confirmClose` — the checklist completes
  only with all tasks resolved (the reopen-approval and checklist-suite legs are
  gap-logged: G-10/G-11).
- **Reports** — `trialBalance`, `arAging` (bucketed aging over POSTED invoices, the
  Phase 5 exit artifact), `inventoryValuation`; the `exec` dashboard; `nightlyAging`
  scheduled delivery under the `reporting` role.
- **Integrations** — the `bankFeed` REST connector driven by the hourly
  `bankFeedSync` scheduled job (T8's §5 wiring: target `flow` firing the
  `Payment.syncBankFeed` hook — `callConnector` → iterate the response's
  transactions → a `Payment` per row; there is no `connector` target by design,
  §5 — with a re-pull's duplicates rejecting audibly per G-14) and the
  `paymentsFeed` inbound webhook (`Payment` upsert, HMAC per PHASE-6 §5 — the
  idempotent push path the bankFeed suite pins).

## Loading (through the definition APIs, or the builder's entity/integration editors — G-7 closed)

```bash
TOKEN=<builder token>   # scratch tenant admin, or the dev workspace builder
APP_ID=$(curl -s -X POST $MD/api/v1/metadata/apps -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d @apps/erp/erp-app.json | jq -r .id)
for s in reconciliation controls inventoryCosting bankFeed creditAndCurrency; do
  curl -s -X PUT $MD/api/v1/metadata/apps/$APP_ID/test-suites/$s \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d @apps/erp/suites/$s.json > /dev/null
done
curl -s -X POST $MD/api/v1/metadata/apps/$APP_ID/publish -H "Authorization: Bearer $TOKEN"
curl -s -X POST $MD/api/v1/metadata/apps/$APP_ID/test-suites/reconciliation/run \
  -H "Authorization: Bearer $TOKEN" | jq .green
```

The suites run through the real harness against a fresh scratch tenant (ADR-010 #3) —
synthetic actors per role, the candidate published, approvals resolved through the
inbox API. `ErpAppArtifactTests` (metadata-service) gates the artifact in CI through
the exact save/compile checks the builder would run.

**Seed fixtures** (§7): the suites carry their own fixtures (chart-of-accounts rows,
customers, items); the opening-balance fixture set joins with the bank-feed journey
(T8's live walkthrough leg).
