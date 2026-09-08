# The ERP Dogfood App (`apps/erp`)

The Phase 7 proving-ground artifact (PHASE-7 §2): a mini-ERP authored **entirely as
metadata** — entities, relationships, state machines, posting flows, roles, sequences,
reports, dashboard, scheduled delivery, integrations — with zero handwritten
application code and zero escape-hatch scripts (the costing hook entered as G-2's
one budgeted script and left with the G-2 harvest — §3.7's declarative `bind`
flow, adopted 2026-09-03; 0 of 4 hooks, the §1 rule 3 ≤ 20% ceiling holds;
reported per module in change-set review, PHASE-7 §9 item 7).

| File | What it is |
|------|------------|
| `erp-app.json` | The app definition (GL, AR, Inventory, Periods, Settings, Permissions, reports, dashboard, job, integrations, close-checklist workflow) |
| `suites/reconciliation.json` | The §9 item 1/5 exit contract: book → approval → **auto-journal** (`createRecord` from templates, G-1 adopted) → journal approval → POSTED → trial balance nets zero, aging reconciles, SoD |
| `suites/controls.json` | §9 items 2–3: posting immutability (`RECORD_FROZEN`) + period locking (`PERIOD_LOCKED`, incl. §4's soft close — `CLOSING` blocks postings unless `closeJournal`) + reopen |
| `suites/inventoryCosting.json` | §9 item 4: receipt → issue at weighted average, decimal-exact through the rounding chain |
| `suites/bankFeed.json` | PHASE-6 T10 / §5 T8: webhook payment (real HMAC path) → settlement → aging reflects it, decimal-exact |
| `suites/creditAndCurrency.json` | §9 item 6 + the AR/AP rows: credit-note allocation, EUR invoice at the document rate posting in USD book currency (the auto-journal's `totalBook` conversion), dunning mirroring its aging bucket, the AP vendor subledger |
| `suites/glLedgerEdges.json` | The GL workflow edges: gapless numbering never burns or gaps (failed creates included), zero-total entries have no approval to fire, unlisted machine jumps (`STATE_TRANSITION`), reject → resubmit → post, stale-version writes/deletes (`CONFLICT_VERSION`) with the draft cascade delete, role walls (chart-of-accounts writes, report execution) and dangling references, the period machine's admitted edges only |
| `suites/arDocumentEdges.json` | The AR document edges: invoice/credit-note/dunning validation rules at the door, rejected invoice creates no journal and the resubmit posts exactly one, the inventory clerk's AR walls (no writes, no reads, no task resolution), credit-note settlement with the `CN-` numbering pin, overpayment landing with no allocation engine yet (G-3/G-9's logged v1 behavior), field-level uniqueness walls + G-16's honest composite-unique pin |
| `suites/inventoryCostingEdges.json` | The costing edges: blended weighted average across receipts, draft movements never count into stock (§3.5's conditional roll-ups, divisor pinned through the issue cost), movement validation rules, the empty-stock issue (no costing, null cost/value) and the explicit-cost issue (keeps its cost, skips the value stamp) |
| `suites/orderToCash.json` | The O2C e2e-cycle suite (`ErpOrderToCashE2ETest`): invoice → approval → auto-journal → journal approval → payment application (partial, then in full closing the invoice) → trial balance / A-R aging |
| `suites/recordToReport.json` | The R2R e2e-cycle suite (`ErpRecordToReportE2ETest`): postings pin the P&L and ledger, soft close with the close-journal exemption, hard lock, reopen re-admitting writes |
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
  (append-only movements, terminal `POSTED` + `freezeOnTerminal`). The costing hook
  (`costMovement`, beforeSave) is §3.7's declarative `bind` flow (G-2's harvest —
  historically §5's canonical escape-hatch case, the one budgeted script): a posted
  issue binds the `Item` roll-up and prices at `inventoryValue / qtyOnHand` (§3.5's
  conditional roll-ups dropped the manual-row discount from the divisor), a receipt
  stamps `value = qty × unitCost` — the exact numbers the retired script produced,
  pinned unchanged by the `inventoryCosting` suite.
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
for s in reconciliation controls inventoryCosting bankFeed creditAndCurrency \
         glLedgerEdges arDocumentEdges inventoryCostingEdges orderToCash recordToReport; do
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
