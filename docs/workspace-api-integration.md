# Workspace → Enrichment API Integration Guide

**For**: enrichment-workspace plugin developers
**API Spec**: `enrichment-api-specification.md` v2.1.0
**Operations Spec**: `WORKSPACE-OPERATIONS-SPEC.md` v1.0
**Date**: 2026-03-08

---

## How to Call the API

All write operations go through a single endpoint due to Joget API Builder routing limitations (see `CLAUDE.md`):

```
GET /records?save=<url-encoded-json>
```

The `save` parameter contains a JSON string. The API dispatches based on the JSON structure:

| Dispatch Key | Triggers | Section |
|---|---|---|
| `{create:true, fields:{...}}` | Create record | §C |
| `{split:true, id:"...", allocations:[...]}` | Split | §B |
| `{merge:true, sourceIds:"id1,id2", mergedFields:{...}}` | Merge | Spec §5.12 |
| `{delete:true, id:"..."}` | Delete | Spec §5.7 |
| `{statusTransition:true, id:"...", targetStatus:"..."}` | Single status transition | Spec §5.5 |
| `{confirm:true, recordIds:["..."]}` | Confirm for posting | Spec §5.10 |
| `{targetStatus:"...", recordIds:["..."]}` | Batch status transition | Spec §5.6 |
| `{id:"...", version:N, field:"value"}` | Inline save (fallback) | §A |

Read operations:
- `GET /records?filter=...&page=...` — list records
- `GET /summary` — per-statement counts
- `GET /reconciliation/{statementId}` — reconciliation data

---

## A. Field Edits (WORKSPACE-OPERATIONS-SPEC §2)

All §2 operations use **inline save** — the workspace changes fields on a record and saves.

### A.1 Reclassify Transaction Type (§2.1)

```json
{
  "id": "ENR-042",
  "version": 3,
  "internal_type": "LOAN_PAYMENT",
  "type_confidence": "manual",
  "matched_rule_id": "",
  "processing_notes": "[2026-03-08 14:23 analyst@co] Reclassified from UNCLASSIFIED to LOAN_PAYMENT: matches loan contract L-001"
}
```

**Fields used**: `internal_type`, `type_confidence`, `matched_rule_id`, `processing_notes`

### A.2 Reassign Customer (§2.2)

```json
{
  "id": "ENR-042",
  "version": 3,
  "resolved_customer_id": "CUST-001",
  "customer_code": "ACME-001",
  "customer_display_name": "ACME Corporation OÜ",
  "customer_match_method": "MANUAL",
  "processing_notes": "[2026-03-08 14:25 analyst@co] Customer reassigned from AUTO-MATCH to ACME-001: description mentions ACME project"
}
```

**Fields used**: `resolved_customer_id`, `customer_code`, `customer_display_name`, `customer_match_method`, `processing_notes`

### A.3 Edit Amounts (§2.3)

```json
{
  "id": "ENR-042",
  "version": 3,
  "original_amount": "10000.00",
  "fee_amount": "50.00",
  "total_amount": "10050.00",
  "base_amount_eur": "10753.50"
}
```

**Fields used**: `original_amount`, `fee_amount`, `total_amount`, `base_amount_eur`

**Note**: The workspace should compute `base_amount_eur` = `total_amount` x `fx_rate_to_eur` before sending. The API stores whatever value is sent.

### A.4 Flip Debit/Credit (§2.4)

```json
{
  "id": "ENR-042",
  "version": 3,
  "debit_credit": "C",
  "processing_notes": "[2026-03-08 14:30 analyst@co] D/C flipped from D to C"
}
```

### A.5 Override FX Rate (§2.5)

```json
{
  "id": "ENR-042",
  "version": 3,
  "fx_rate_to_eur": "1.068380",
  "fx_rate_date": "2026-03-08",
  "fx_rate_source": "manual",
  "base_amount_eur": "10753.50",
  "base_fee_eur": "53.42",
  "requires_eur_parallel": "yes"
}
```

**Fields used**: `fx_rate_to_eur`, `fx_rate_date`, `fx_rate_source`, `base_amount_eur`, `base_fee_eur`, `requires_eur_parallel`

### A.6 Override GL Account (§2.6)

```json
{
  "id": "ENR-042",
  "version": 3,
  "gl_debit_override": "1001.LHV-EE",
  "gl_credit_override": "3102.12345678",
  "gl_override_reason": "Special one-off regulatory reporting reclassification"
}
```

**Fields used**: `gl_debit_override`, `gl_credit_override`, `gl_override_reason`

### A.7 Add Processing Note (§2.7)

```json
{
  "id": "ENR-042",
  "version": 3,
  "processing_notes": "[2026-03-08 14:35 analyst@co] Checked with fund manager — correct as-is"
}
```

**Note**: `processing_notes` is always editable, even in terminal statuses (CONFIRMED, SUPERSEDED) and restricted statuses (PROCESSING, ERROR). The workspace should append to the existing value, not replace it.

---

## B. Split Operations (WORKSPACE-OPERATIONS-SPEC §3)

All split variants use the same API call. The workspace differentiates them in the UI; the API sees allocations with optional per-child field overrides.

### B.1 Generic Split (§3.1)

```json
{
  "split": true,
  "id": "ENR-042",
  "allocations": [
    {"customer_code": "CUST-001", "original_amount": "25000.00", "fee_amount": "62.50"},
    {"customer_code": "CUST-002", "original_amount": "25000.00", "fee_amount": "62.50"}
  ]
}
```

### B.2 Loan Payment Split (§3.2)

Per-child `internal_type`, `loan_id`, `loan_direction`, and `description` overrides:

```json
{
  "split": true,
  "id": "ENR-042",
  "allocations": [
    {
      "customer_code": "CUST-001",
      "original_amount": "8000.00",
      "fee_amount": "0",
      "internal_type": "LOAN_PAYMENT",
      "loan_id": "LOAN-001",
      "loan_direction": "LENDER",
      "description": "Principal repayment — LOAN-001"
    },
    {
      "customer_code": "CUST-001",
      "original_amount": "1500.00",
      "fee_amount": "0",
      "internal_type": "INT_INCOME",
      "loan_id": "LOAN-001",
      "loan_direction": "LENDER",
      "description": "Interest income — LOAN-001 Q1 2026"
    },
    {
      "customer_code": "CUST-001",
      "original_amount": "500.00",
      "fee_amount": "0",
      "internal_type": "COMM_FEE",
      "description": "Late payment penalty"
    }
  ]
}
```

### B.3 Multi-Period Accrual Split (§3.3)

Per-child `transaction_date` override puts children in different accounting periods:

```json
{
  "split": true,
  "id": "ENR-042",
  "allocations": [
    {
      "customer_code": "CUST-001",
      "original_amount": "500.00",
      "fee_amount": "0",
      "transaction_date": "2026-01-31",
      "description": "Interest accrual — January 2026"
    },
    {
      "customer_code": "CUST-001",
      "original_amount": "500.00",
      "fee_amount": "0",
      "transaction_date": "2026-02-28",
      "description": "Interest accrual — February 2026"
    },
    {
      "customer_code": "CUST-001",
      "original_amount": "500.00",
      "fee_amount": "0",
      "transaction_date": "2026-03-31",
      "description": "Interest accrual — March 2026"
    }
  ]
}
```

### B.4 Fee Disaggregation (§3.4)

Per-child `gl_debit_override` / `gl_credit_override` for different GL targets:

```json
{
  "split": true,
  "id": "ENR-042",
  "allocations": [
    {
      "customer_code": "CUST-001",
      "original_amount": "3000.00",
      "fee_amount": "0",
      "internal_type": "ADMIN_FEE",
      "gl_debit_override": "4201",
      "description": "Custody fee portion"
    },
    {
      "customer_code": "CUST-001",
      "original_amount": "2000.00",
      "fee_amount": "0",
      "internal_type": "LEGAL_FEE",
      "gl_debit_override": "4203",
      "description": "Legal fee portion"
    }
  ]
}
```

### Split Response

All split variants return the same structure:

```json
{
  "parentId": "ENR-042",
  "parentStatus": "superseded",
  "children": [
    {"id": "ENR-042-S1", "customer_code": "CUST-001", "original_amount": 8000.0, "split_sequence": 1},
    {"id": "ENR-042-S2", "customer_code": "CUST-001", "original_amount": 1500.0, "split_sequence": 2}
  ],
  "ms": 180
}
```

### Split Rules

- Minimum 2 allocations
- `customer_code` required on every allocation
- Sum of `original_amount` must equal parent's `original_amount` (±0.01 tolerance)
- Fee rounding remainder auto-assigned to last child
- Parent transitions to SUPERSEDED; children start as ENRICHED
- Per-child overrides: any field in EDITABLE_FIELDS is accepted; non-editable fields are silently ignored
- Protected fields (id, status, version, lineage fields) cannot be overridden — they are set by the API

---

## C. Create Record (WORKSPACE-OPERATIONS-SPEC §4.3, §7.3, §8.1, §8.2)

Used for manual entries that the workspace creates programmatically.

### C.1 Interest Accrual (§8.1)

```json
{
  "create": true,
  "fields": {
    "internal_type": "INT_INCOME",
    "debit_credit": "C",
    "total_amount": "1500.00",
    "original_amount": "1500.00",
    "validated_currency": "EUR",
    "transaction_date": "2026-03-31",
    "statement_id": "STMT-2026-03",
    "description": "Interest accrual LOAN-001 March 2026",
    "loan_id": "LOAN-001",
    "loan_direction": "LENDER",
    "customer_code": "CUST-001",
    "resolved_customer_id": "CUST-001"
  }
}
```

### C.2 Accrual Reversal (§8.2)

```json
{
  "create": true,
  "fields": {
    "internal_type": "INT_INCOME",
    "debit_credit": "D",
    "total_amount": "1500.00",
    "original_amount": "1500.00",
    "validated_currency": "EUR",
    "transaction_date": "2026-04-01",
    "statement_id": "STMT-2026-04",
    "description": "Reversal of interest accrual LOAN-001 March 2026",
    "acc_post_id": "a1b2c3d4-original-accrual-id",
    "loan_id": "LOAN-001",
    "customer_code": "CUST-001",
    "resolved_customer_id": "CUST-001"
  }
}
```

### C.3 FX Gain/Loss Entry (§7.3)

```json
{
  "create": true,
  "fields": {
    "internal_type": "FX_EXCHANGE",
    "debit_credit": "C",
    "total_amount": "55.12",
    "original_amount": "55.12",
    "validated_currency": "EUR",
    "transaction_date": "2026-03-31",
    "statement_id": "STMT-2026-03",
    "description": "FX gain on USD settlement 2026-03-15",
    "source_reference": "original-fx-pair-id",
    "gl_debit_override": "1001.LHV-EE",
    "gl_credit_override": "3401"
  }
}
```

### Create Response

```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "enriched",
  "version": 0,
  "source_tp": "manual",
  "internal_type": "INT_INCOME",
  "...": "...",
  "ms": 45
}
```

### Create Rules

- **Mandatory**: `internal_type`, `debit_credit`, `total_amount`, `validated_currency`, `transaction_date`, `statement_id`
- **Enforced**: `source_tp` is always set to "manual" (cannot be overridden)
- **Status**: Always starts as ENRICHED
- **Version**: Always starts at 0
- **Protected fields** (cannot be set by caller): `id`, `status`, `version`, `origin`, `parent_enrichment_id`, `group_id`, `split_sequence`, `confirmed_by`, `confirmed_at`
- **Filtered**: Only fields in EDITABLE_FIELDS (§4.3) pass through; others are silently dropped

---

## D. Loan Contract Operations (WORKSPACE-OPERATIONS-SPEC §4)

### D.1 Link to Loan Contract (§4.1)

Uses inline save:

```json
{
  "id": "ENR-042",
  "version": 3,
  "loan_id": "LOAN-001",
  "loan_direction": "LENDER",
  "loan_resolution_method": "MANUAL",
  "processing_notes": "[2026-03-08 analyst@co] Linked to LOAN-001 (manually matched)"
}
```

### D.2 Verify Interest / Update Balance / Flag Early Repayment (§4.2–4.4)

These are **workspace-only** operations — they read loan contract data (F02.04) and display verification results. They do NOT call the enrichment API.

---

## E. Fund Allocation (WORKSPACE-OPERATIONS-SPEC §5)

### E.5 Mark Fund Transaction as Allocated (§5.5)

After the workspace creates `fundAllocation` records in F03.03 (which it manages directly), it marks the source F01.05 record:

```json
{
  "id": "ENR-042",
  "version": 3,
  "fund_allocation_status": "allocated"
}
```

### E.1–E.4, E.6 (§5.1–5.4, §5.6)

These operate on **separate forms** (investorPosition, navCalculation, fundAllocation) that the workspace manages directly. They do NOT go through the enrichment API.

---

## F. Securities & Pairing (WORKSPACE-OPERATIONS-SPEC §6)

### F.1 Manual Pair / F.2 Unpair (§6.1, §6.2) — DEFERRED

Atomic pairing (setting `pair_id` on two records and transitioning both to PAIRED) requires architectural discussion. For now, `pair_id` is in EDITABLE_FIELDS, so the workspace can set it manually on individual records via inline save:

```json
{"id": "ENR-042", "version": 3, "pair_id": "PAIR-UUID-001"}
```

**Limitation**: This is not atomic — if one save succeeds and the other fails, the records are inconsistent. A proper pair/unpair endpoint will be designed separately.

### F.3 Link COMM_FEE to Trade (§6.3)

```json
{
  "id": "ENR-042",
  "version": 3,
  "source_reference": "trade-enrichment-id",
  "processing_notes": "[2026-03-08 analyst@co] Linked to trade ENR-100 (CRWD)"
}
```

### F.4 Link DIV_TAX to Dividend (§6.4)

```json
{
  "id": "ENR-042",
  "version": 3,
  "source_reference": "dividend-enrichment-id"
}
```

---

## G. FX Operations (WORKSPACE-OPERATIONS-SPEC §7)

### G.1 Pair FX Legs (§7.1)

Same approach as securities pairing — uses `source_reference` and `pair_id` via inline save. Atomic pairing deferred.

```json
{"id": "ENR-042", "version": 3, "source_reference": "other-fx-leg-id", "pair_id": "FX-PAIR-001"}
```

### G.2 Override Exchange Rate (§7.2)

Same as §A.5 (FX Override).

### G.3 Create FX Gain/Loss Entry (§7.3)

See §C.3 above.

---

## H. Period-End Operations (WORKSPACE-OPERATIONS-SPEC §8)

### H.1 Create Accrual Entry (§8.1)

See §C.1 above.

### H.2 Reverse Prior Accrual (§8.2)

See §C.2 above.

### H.3 Lock Period (§8.3) — DEFERRED

Period lock management (reading/writing `periodLock` form F03.04, setting `period_locked` on F01.05 records) is a workspace responsibility. The `period_locked` field is in EDITABLE_FIELDS so the workspace can set it, but the period lock guard (rejecting edits on locked records) is not yet implemented in the API.

### H.4 Reclassify Between Periods (§8.4)

Uses inline save to change `transaction_date`:

```json
{
  "id": "ENR-042",
  "version": 3,
  "transaction_date": "2026-02-28",
  "processing_notes": "[2026-03-08 analyst@co] Moved from March to February period"
}
```

---

## I. Batch & Workflow (WORKSPACE-OPERATIONS-SPEC §9)

### I.1 Bulk Mark Ready (§9.1)

```json
{
  "targetStatus": "ready",
  "recordIds": ["ENR-001", "ENR-002", "ENR-003"],
  "reason": "Batch reviewed and approved"
}
```

### I.2 Bulk Confirm (§9.2)

```json
{
  "confirm": true,
  "recordIds": ["ENR-001", "ENR-002", "ENR-003"],
  "allowPartial": true
}
```

### I.3 Re-enrich (§9.3)

```json
{
  "targetStatus": "new",
  "recordIds": ["ENR-ERR-001"],
  "reason": "Re-enrich after fix"
}
```

### I.4 Export (§9.4)

The workspace fetches records via `GET /records?filter=...&pageSize=1000` and formats the export itself. No special API endpoint needed.

---

## What Does NOT Go Through enrichment-api

These are workspace or separate plugin responsibilities — the workspace handles them directly:

| Operation | Form/Data | Why not enrichment-api |
|---|---|---|
| Customer lookup (§2.2 search) | `customer` form | Read-only lookup of separate form |
| Loan contract lookup (§4.1 search) | `loanContract` (F02.04) | Separate form, read-only |
| Interest verification (§4.2) | `loanContract` | Read-only calculation |
| Outstanding balance update (§4.3) | `loanContract` | Writes to different form |
| Early repayment detection (§4.4) | `loanContract` | Read-only analysis |
| GL account lookup (§2.6 search) | CoA accounts | Separate form, read-only |
| Investor position CRUD (§5.1–5.3) | `investorPosition` (F03.01) | Separate form |
| NAV calculation (§5.4) | `navCalculation` (F03.02) | Separate form |
| Fund allocation records (§5.5) | `fundAllocation` (F03.03) | Separate form |
| Period lock management (§8.3) | `periodLock` (F03.04) | Separate form |
| Export to CSV/XLSX (§9.4) | — | Client-side formatting |

---

## Deferred Items

| Item | Status | Notes |
|---|---|---|
| Atomic pair/unpair endpoint | Needs architecture discussion | Currently `pair_id` editable but no atomic 2-record operation |
| Period lock guard | Deferred | API does not reject edits on `period_locked=yes` records yet |
| Enhanced confirmation validation | Deferred | Reject `internal_type=UNCLASSIFIED` on confirm; require `loan_id` for loan types |
| Conditional requirement: `forbiddenValue` operator | Deferred | ValidationConfig can't express "field must NOT equal X" |
