# BuildRight P2P Gap Log — wave 1

> The portfolio app follows the same discipline as the Phase 7 dogfood
> (`apps/erp/GAP-LOG.md`, PHASE-7 §1 rule 2): **every gap becomes a log entry
> before any workaround**, triage assigns
> `accept-as-platform-feature | backlog | wontfix-with-workaround`, and the same
> entries ride `buildright-app.json`'s `gapLog` branch as versioned metadata.
> Keep the two in sync when triage moves.

| # | Area | Blocker | Workaround in wave 1 | Proposed primitive / flag | Priority | Disposition |
|---|------|---------|--------------------------|---------------------------|----------|-------------|
| BR-G-1 | P1/P8 Apps | **Cross-app record creation:** one app's flows cannot create records in another app's entities (`createRecord` resolves entities within the owning app), so the P2P app cannot post its auto-journal into the Erp dogfood app's GL — every domain app must carry its own GL skeleton until an app-to-app primitive exists | Wave 1 ships a self-contained P2P app with its own minimal GL (Account/JournalEntry/JournalLine mirroring the proven Erp patterns); consolidation across domain GLs is out of scope until the primitive lands | Cross-app `createRecord` (namespaced entity refs + target-app permission binding), or app-to-app event contracts consumed by flows | **high** | backlog |
| BR-G-2 | P3 Business Logic | **Cross-document quantity guards and roll-ups:** the 3-way match (bill qty ≤ receipt qty ≤ ordered qty) and the automatic receipt-side update of `PurchaseOrderLine.qtyReceived` need aggregate queries over lookup traversals (sum receipts by poLine) or writes to a bound record — the bind primitive reads a bound target's view but cannot write it, and report vocabulary cannot join lookups (the Erp G-12 twin) | Receipt carries denormalized `po` + `poLine` + `item` lookups (consistency by authoring discipline, pinned by the suites); `PurchaseOrderLine.qtyReceived` is clerk-maintained; over-receipt and bill-over-receipt mismatches ride the bill approval gate (procurementManager sees the documents) instead of a hard guard; the overpay edge (outstanding goes negative) is pinned as a suite assertion so the gap stays visible | An aggregate-query flow step over lookup paths (e.g. `sumLookups`), or write-back bindings on `bind`, or lookup-path roll-ups | **high** | backlog |
| BR-G-3 | AP | **Payment allocation across multiple bills:** a payment split over several bills needs the Erp G-9 allocate primitive; wave 1 payments bind exactly one bill | One payment per bill (the dominant AP case for merchandise invoices); split payments are logged, not automated | The `allocate` primitive (payment → bills, FIFO or specific), shared with the Erp gap | medium | backlog |
| BR-G-4 | GL | **Period lock:** the P2P app's GL carries no AccountingPeriod/`periodLock` — dated-write control for bill journals rides the Erp app's proven `periodLock` harvest only after BR-G-1 (cross-app) or a shared GL lands | Bill journal dates are clerk-disciplined; the suite corpus pins current-period dates | Adopt the `periodLock` block (entity/dateField/restrictedStatus/exemptField) once the GL home is settled | medium | backlog |

## Suite-pinned workarounds (read the suites as the spec of the workaround)

- `p2pReceivingBillingEdges.json` posts a receipt against a **DRAFT** purchase
  order and pins it green — that is BR-G-2's workaround made executable: until
  cross-document guards exist, receiving discipline is procedural, not enforced.
- The same suite pins an **overpayment** (`amountOutstanding == -100.0000`) so
  the missing hard guard cannot regress silently.
- `p2pApprovalEdges.json` pins the SoD rejection
  (`error(SOD_VIOLATION)`) — the approval gate is the compensating control that
  makes the procedural workarounds auditable.
