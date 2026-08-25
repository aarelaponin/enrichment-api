# SPEC: Income Allocation (D2)

**Component:** enrichment-api (backend) + enrichment-workspace (frontend)
**Date:** 2026-03-15
**Author:** Aare Lapõnin / Claude
**Status:** Draft

---

## 1. Overview

When a dividend or interest income transaction (e.g. DIV_INCOME for ADBE) arrives, the system should be able to automatically allocate it across all customers who held positions in the related asset during the accrual period. This eliminates manual per-customer income splitting.

### User flow

1. Income transaction arrives via statement import (e.g. "Dividends (ADBE)", $81.25)
2. Enrichment pipeline resolves the asset (ADBE → AST000296)
3. User opens the income transaction in the Enrichment Workspace
4. User clicks **"Allocate Income"** and specifies:
   - **Accrual period start** (e.g. ex-dividend date or period start)
   - **Accrual period end** (e.g. record date or period end)
5. System automatically:
   a. Finds all customers who held the asset during the period (from allocation lots)
   b. Computes each customer's **share-days** (quantity × days held within the period)
   c. Calculates each customer's **allocation percentage** (their share-days ÷ total share-days)
   d. Allocates the income amount proportionally
   e. Creates `incomeAllocation` records (F03.03) for each customer
   f. Updates the enrichment record's `fund_allocation_status`

---

## 2. Eligible Transaction Types

| Type | Direction | Has Asset | Description |
|------|-----------|-----------|-------------|
| `DIV_INCOME` | Credit (C) | Yes (6/7) | Equity dividends |
| `DIV_TAX` | Debit (D) | Yes (5/5) | Withholding tax on dividends |
| `BOND_INT` | Credit (C) | No* | Bond coupon interest |

*BOND_INT transactions currently have resolved_customer_id but no resolved_asset_id. They may need asset resolution before income allocation applies, or they follow a different path (direct customer assignment).*

### Out of scope (handled differently)

| Type | Reason |
|------|--------|
| `INT_INCOME` | Loan interest — already has customer, allocated directly to lender |
| `INT_EXPENSE` | Loan interest paid — already has customer, allocated directly to borrower |
| `INV_INCOME` | Standalone investment income — already customer-attributed |
| `TAX` | General tax — already customer-attributed |

---

## 3. Data Model

### 3.1 Source: Enrichment Record (F01.05 `trxEnrichment`)

Fields used:

| Field | Usage |
|-------|-------|
| `id` | Source enrichment ID (e.g. TRX-9395BD) |
| `internal_type` | Must be in eligible types (DIV_INCOME, DIV_TAX, BOND_INT) |
| `status` | Must be in eligible statuses (enriched, adjusted, in_review, ready, paired) |
| `fund_allocation_status` | null/pending → partial/allocated after processing |
| `resolved_asset_id` | Links to the asset (e.g. AST000296 for MU) |
| `total_amount` | Total income amount to allocate (e.g. 81.25) |
| `original_currency` | Currency (e.g. USD) |
| `fx_rate_to_eur` | For EUR conversion |
| `transaction_date` | Date of the income event |

### 3.2 Position Source: Allocation Lots (F03.02 `allocationLot`)

The allocation lots provide historical position data — who bought/sold what and when. This is the **primary source** for reconstructing holdings during the accrual period.

Key fields:

| Field | Usage |
|-------|-------|
| `customerId` | Customer code |
| `assetTicker` / `assetId` | Asset identifier |
| `direction` | BUY or SELL |
| `quantity` | Number of units in this lot |
| `allocationDate` | When the trade was allocated (position effective date) |

### 3.3 Target: Income Allocation (F03.03 `incomeAllocation`)

Existing table, currently empty. Each row represents one customer's share of an income event.

| Field | Type | Description |
|-------|------|-------------|
| `incomeAllocId` | String | Generated ID (e.g. IA-000001) |
| `sourceEnrichmentId` | String | FK → trxEnrichment.id |
| `customerId` | String | Customer code |
| `customerDisplayName` | String | Customer display name |
| `assetId` | String | Asset ID |
| `assetTicker` | String | Asset ticker symbol |
| `currency` | String | Original currency |
| `accrualPeriodStart` | Date | Start of the income accrual period |
| `accrualPeriodEnd` | Date | End of the income accrual period |
| `holdingDays` | Integer | Number of days customer held during period |
| `averageQuantityHeld` | Decimal | Average qty held across the period |
| `shareDays` | Decimal | quantity × days = customer's share-days |
| `totalShareDays` | Decimal | Sum of all customers' share-days (denominator) |
| `allocationPercentage` | Decimal | shareDays / totalShareDays (0.00–1.00) |
| `allocatedAmount` | Decimal | Proportional income in original currency |
| `allocatedAmountEur` | Decimal | Proportional income in EUR |
| `allocationDate` | Date | When allocation was performed |
| `status` | String | "allocated" |

---

## 4. Allocation Algorithm

### 4.1 Reconstruct Holdings from Allocation Lots

Since there is no daily position snapshot table, holdings are reconstructed from BUY/SELL allocation lots:

```
For a given (assetId, accrualPeriodStart, accrualPeriodEnd):

1. Find all allocation lots for this asset
2. For each customer, build a timeline of position changes:
   - BUY lot on date D → position increases by lot.quantity on D
   - SELL lot on date D → position decreases by lot.quantity on D
3. For each day in [periodStart, periodEnd]:
   - Customer's holding = running sum of BUY quantities − SELL quantities
     for all lots with allocationDate <= that day
4. shareDays = SUM(daily quantity held) across all days in period
```

### 4.2 Allocation Calculation

```
Given:
  totalAmount     = enrichment.total_amount (e.g. 81.25 USD)
  fxRate          = enrichment.fx_rate_to_eur
  customers[]     = list of customers with shareDays > 0

For each customer:
  allocationPct   = customer.shareDays / SUM(all shareDays)
  allocatedAmount = totalAmount × allocationPct     (rounded to 6 decimals)
  allocatedAmtEur = allocatedAmount × fxRate         (rounded to 6 decimals)
  holdingDays     = number of days in period where qty > 0
  avgQtyHeld      = customer.shareDays / holdingDays
```

### 4.3 Example: ADBE Dividend

Transaction: DIV_INCOME $81.25 for ADBE, transaction date 2024-07-24.
User specifies accrual period: 2024-06-17 to 2024-07-24 (38 days).

Allocation lots for ADBE:

| Lot | Customer | Direction | Qty | Date |
|-----|----------|-----------|-----|------|
| LOT-000006 | 11910225 | BUY | 15 | 2024-06-17 |
| LOT-000007 | 11223344 | BUY | 5 | 2024-06-17 |
| LOT-000010 | 11910225 | SELL | 10 | 2024-07-24 |
| LOT-000011 | 11223344 | SELL | 5 | 2024-07-24 |
| LOT-000012 | 11910225 | SELL | 5 | 2024-07-24 |

Holdings reconstruction (2024-06-17 to 2024-07-24):

| Customer | Days 06-17 to 07-23 (37d) | Day 07-24 (1d) | Share-Days |
|----------|---------------------------|----------------|------------|
| 11910225 | 15 × 37 = 555 | 0 × 1 = 0 | 555 |
| 11223344 | 5 × 37 = 185 | 0 × 1 = 0 | 185 |

Total share-days: 740

| Customer | Share-Days | Pct | Allocated |
|----------|-----------|-----|-----------|
| 11910225 | 555 | 75.00% | $60.94 |
| 11223344 | 185 | 25.00% | $20.31 |

---

## 5. API Design

### 5.1 Endpoint: Allocate Income

Reuses the existing `save` parameter dispatch pattern on `GET /jw/api/enrichment/records`.

**Request:**
```json
{
  "allocateIncome": true,
  "enrichmentId": "TRX-9395BD",
  "accrualPeriodStart": "2024-06-17",
  "accrualPeriodEnd": "2024-07-24"
}
```

**Success response (200):**
```json
{
  "success": true,
  "enrichmentId": "TRX-9395BD",
  "asset": "ADBE",
  "totalAmount": 81.25,
  "currency": "USD",
  "accrualPeriodStart": "2024-06-17",
  "accrualPeriodEnd": "2024-07-24",
  "totalShareDays": 740.0,
  "allocations": [
    {
      "incomeAllocId": "IA-000001",
      "customerId": "11910225",
      "customerName": "ADVERTA GRUPP OÜ",
      "shareDays": 555.0,
      "allocationPct": 0.75,
      "allocatedAmount": 60.94,
      "allocatedAmountEur": 56.07
    },
    {
      "incomeAllocId": "IA-000002",
      "customerId": "11223344",
      "customerName": "ASKEMBLA ASSET MANAGEMENT OÜ",
      "shareDays": 185.0,
      "allocationPct": 0.25,
      "allocatedAmount": 20.31,
      "allocatedAmountEur": 18.69
    }
  ],
  "allocationStatus": "allocated",
  "ms": 45
}
```

**Error responses:**

| Error | When |
|-------|------|
| `VALIDATION_FAILED: Record type 'X' not eligible for income allocation` | Type not in eligible list |
| `VALIDATION_FAILED: No asset linked to enrichment record` | resolved_asset_id is empty |
| `VALIDATION_FAILED: No holdings found for asset X in period Y–Z` | No allocation lots match |
| `VALIDATION_FAILED: Accrual period end must be after start` | Date validation |
| `VALIDATION_FAILED: Income already fully allocated` | fund_allocation_status = "allocated" |

### 5.2 Endpoint: Get Income Allocation Summary

For displaying existing allocations in the dialog.

**Request:**
```json
{
  "incomeAllocationSummary": true,
  "enrichmentId": "TRX-9395BD"
}
```

**Response (200):**
```json
{
  "enrichmentId": "TRX-9395BD",
  "totalAmount": 81.25,
  "allocatedAmount": 81.25,
  "allocationStatus": "allocated",
  "allocations": [
    {
      "incomeAllocId": "IA-000001",
      "customerId": "11910225",
      "customerName": "ADVERTA GRUPP OÜ",
      "allocationPct": 0.75,
      "allocatedAmount": 60.94
    }
  ]
}
```

---

## 6. Frontend: Income Allocation Dialog

### 6.1 Existing UI Scaffolding (already works)

The enrichment-workspace already has most of the UI plumbing in place for DIV_INCOME:

| Component | File | Status | Notes |
|-----------|------|--------|-------|
| Type category mapping | `ew-dashboard.js:16` | ✅ Done | `DIV_INCOME: 'fund'` |
| Menu item "Allocate to Investors" | `ew-actions.js:84` | ✅ Done | `needsFund: true` — enabled for fund types |
| `isFundSel()` gate | `ew-actions.js:31` | ✅ Done | Returns true for DIV_INCOME via category |
| Blue ribbon "Fund transaction — allocate to investors" | `ew-detail.js:253` | ✅ Done | Shows for unallocated fund types |
| Green ribbon "Fully allocated" | `ew-detail.js:242` | ✅ Done | Shows when `fund_allocation_status === 'allocated'` |
| Fund Allocation detail section (§8) | `ew-detail.js:646` | ✅ Done | Shows status for fund transactions |
| Allocation History section (§9) | `ew-detail.js:653` | ✅ Done | Shows after allocation |

**The gap**: Clicking "Allocate to Investors" calls `openFundAllocDialog()` (`ew-actions.js:1961`), which checks `ALLOC_TYPES` (only `EQ_BUY/SELL`, `BOND_BUY/SELL`, `SEC_BUY/SELL`) and blocks income types with "Type not allocatable." The dialog then loads `secuTransaction` data that doesn't exist for income types.

> **Design rule (architectural fix):** An action button must never be enabled if clicking it
> immediately shows an error. The gate logic in the menu (`isFundSel`) and the gate logic
> in the dialog (`ALLOC_TYPES` check) must be consistent. With D2 implemented, the
> `openFundAllocDialog` router handles all fund types — either via trade allocation or
> income allocation — so no enabled button leads to an error. Any future allocation type
> should follow this pattern: add routing in `openFundAllocDialog` BEFORE adding the type
> to the menu/ribbon gates.

### 6.2 Changes Needed

**`ew-actions.js`** — Route income types to a new dialog:

```javascript
// New constant alongside existing ALLOC_TYPES
var INCOME_ALLOC_TYPES = ['DIV_INCOME', 'DIV_TAX', 'BOND_INT'];

// Modified openFundAllocDialog — add routing at the top:
function openFundAllocDialog(selected) {
    ...
    var rec = EW.state.records && EW.state.records[selected[0].id];
    // Route income types to dedicated dialog
    if (INCOME_ALLOC_TYPES.indexOf(rec.internal_type) >= 0) {
        return openIncomeAllocDialog(selected);
    }
    // Existing trade allocation logic continues...
}
```

### 6.2 Dialog Layout

```
┌─────────────────────────────────────────────────┐
│  Income Allocation                          [×]  │
├─────────────────────────────────────────────────┤
│                                                   │
│  📋 Income Summary                               │
│  ┌──────────────────────────────────────────┐    │
│  │ Enrichment ID: TRX-9395BD                │    │
│  │ Type: DIV_INCOME    Asset: MU            │    │
│  │ Amount: $81.25      Currency: USD        │    │
│  │ Date: 2024-07-24                         │    │
│  └──────────────────────────────────────────┘    │
│                                                   │
│  📅 Accrual Period                               │
│  ┌──────────────────────────────────────────┐    │
│  │ Start: [2024-06-17]  End: [2024-07-24]   │    │
│  │                                           │    │
│  │          [🔍 Preview Allocation]          │    │
│  └──────────────────────────────────────────┘    │
│                                                   │
│  📊 Allocation Preview        (after Preview)    │
│  ┌──────────────────────────────────────────┐    │
│  │ Customer         Share-Days  Pct   Amount │    │
│  │ ──────────────── ────────── ───── ─────── │    │
│  │ ADVERTA GRUPP OÜ    555    75.0%  $60.94 │    │
│  │ ASKEMBLA A.M. OÜ    185    25.0%  $20.31 │    │
│  │ ──────────────── ────────── ───── ─────── │    │
│  │ TOTAL                740   100.0%  $81.25 │    │
│  └──────────────────────────────────────────┘    │
│                                                   │
│               [Cancel]  [💰 Allocate Income]      │
└─────────────────────────────────────────────────┘
```

### 6.3 Two-Step Flow

1. **Preview**: User sets the accrual period dates and clicks "Preview Allocation". The system calls a preview endpoint that computes the allocation without writing anything. The table shows who gets what.

2. **Confirm**: User reviews the preview and clicks "Allocate Income". The system writes the `incomeAllocation` records and updates the enrichment record status.

---

## 7. Backend Implementation Plan

### 7.1 EnrichmentService.java

New method: `allocateIncome(enrichmentTable, enrichmentId, periodStart, periodEnd, config)`

**Phases:**

1. **LOAD & VALIDATE** — Load enrichment record, check type/status/allocation status, load asset info
2. **RECONSTRUCT HOLDINGS** — Query allocation lots for the asset, build daily position timeline per customer
3. **COMPUTE SHARES** — Calculate share-days, percentages, allocated amounts
4. **WRITE** — Insert incomeAllocation records, update enrichment fund_allocation_status
5. **RESPOND** — Return allocation summary

### 7.2 ValidationConfig.java

New inner class `IncomeAllocationConfig` with:
- `eligibleTypes`: ["DIV_INCOME", "DIV_TAX", "BOND_INT"]
- `eligibleStatuses`: same as trade allocation
- `incomeAllocTable`: "incomeAllocation"
- `incomeAllocIdFormat`: "IA-######"
- Defaults for all field names

### 7.3 EnrichmentApiPlugin.java

New dispatch routes in `dispatchSaveParam`:
```java
if (peek.has("allocateIncome") && peek.has("enrichmentId")) {
    return handleAllocateIncome(json);
}
if (peek.has("incomeAllocationSummary") && peek.has("enrichmentId")) {
    return handleGetIncomeAllocationSummary(json);
}
if (peek.has("previewIncomeAllocation") && peek.has("enrichmentId")) {
    return handlePreviewIncomeAllocation(json);
}
```

### 7.4 ew-api.js

New API methods:
```javascript
EW.api.previewIncomeAllocation = function(enrichmentId, periodStart, periodEnd) { ... }
EW.api.allocateIncome = function(enrichmentId, periodStart, periodEnd) { ... }
EW.api.getIncomeAllocationSummary = function(enrichmentId) { ... }
```

### 7.5 ew-actions.js

New dialog: `openIncomeAllocDialog(selected)` — similar structure to `openFundAllocDialog` but with the accrual period inputs and preview step.

---

## 8. Edge Cases

| Case | Handling |
|------|----------|
| No allocation lots exist for asset in period | Error: "No holdings found" |
| Customer sold entire position mid-period | Partial share-days (only days held) |
| Customer bought mid-period | Partial share-days (from buy date onward) |
| Multiple BUY lots for same customer | Sum of quantities |
| Period extends before earliest lot | Only count from first lot date |
| Rounding causes total ≠ 100% | Adjust largest allocation by remainder |
| DIV_TAX (negative amount) | Same logic, but allocated amount is negative (tax deduction per customer) |
| Re-allocation after reversal | Delete existing incomeAllocation records, reset fund_allocation_status, re-run |
| Asset not resolved on enrichment | Block allocation with clear error message |

---

## 9. Testing Scenarios

### Scenario 1: ADBE Dividend — Two Customers

- TRX with resolved_asset matching ADBE
- Two customers held ADBE during the period (from existing allocation lots)
- Expected: proportional split based on share-days

### Scenario 2: DIV_TAX — Negative Amount

- Tax withholding on same dividend
- Same customers, same proportions
- Expected: negative allocated amounts matching the tax

### Scenario 3: Single Customer Holds Entire Period

- Only one customer had the position
- Expected: 100% allocation to that customer

### Scenario 4: Customer Sells Mid-Period

- Customer A holds 100 shares for 20 days, sells on day 20
- Customer B holds 0, buys 100 on day 20
- Expected: A gets more share-days (2000 vs however many B has)

---

## 10. Future Enhancements

1. **Daily position snapshots** — If `positionSnapshot` table is populated, use actual daily positions instead of reconstructing from lots. More accurate for complex trading patterns.

2. **Manual override** — Allow user to adjust allocation percentages before confirming.

3. **Batch allocation** — Select multiple income transactions for the same asset/period and allocate them all at once.

4. **Auto-detect accrual period** — For known dividend schedules, pre-fill the ex-dividend date and record date.

5. **BOND_INT asset resolution** — Extend bond interest handling to auto-resolve the bond asset from the description (e.g. "Interest (INBB060029A)" → bond ISIN lookup).
