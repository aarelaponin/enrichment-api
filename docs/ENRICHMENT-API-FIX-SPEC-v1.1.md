# Enrichment-API Plugin — Trade Allocation Fix Specification

**Version:** 1.1
**Date:** 14 March 2026
**Plugin:** enrichment-api
**Depends on:** ASSET-ALLOCATION-API-SPEC.md v1.0, TRADE-ALLOCATION-SPEC.md v1.1
**Priority:** High — data integrity and traceability gaps
**Estimated effort:** 2–3 days development + testing

---

## 1. Executive Summary

After allocating two securities transactions (TRX-2BC7DB equity, TRX-32EC0C bond) to investor customers, analysis of the persisted data in F03.00–F03.02 reveals six categories of defects in the enrichment-api allocation logic. All defects originate in `EnrichmentService.allocateTrade()` and the `getAllocationSummary` API endpoint. The Joget form definitions (F03.00–F03.02) serve only as data model containers and require no changes.

### 1.1 Defect Summary

| # | Defect | Severity | Affected Tables |
|---|--------|----------|-----------------|
| D1 | ID generation silently falls back to UUID | High | F03.00, F03.01, F03.02 |
| D2 | Generated IDs not written to form field columns | High | F03.00, F03.01, F03.02 |
| D3 | EUR equivalent fields are hardcoded empty | High | F03.01, F03.02 |
| D4 | Portfolio cost aggregation mixes currencies | High | F03.00 |
| D5 | Allocation summary API returns minimal lot data | Medium | API response |
| D6 | snapshotDate field never populated | Low | F03.00 |

---

## 2. Defect Analysis

### D1: ID Generation Falls Back to UUID

**File:** `EnrichmentService.java`, lines 1438–1459
**Method:** `generateId(String format, String envVariable)`

The `generateId()` method attempts to use Joget's `EnvironmentVariableDao.getIncrementedValue()` to produce sequential IDs (`LOT-000001`, `PP-000001`, `CPF-000001`). When the call fails, the catch block silently falls back to `UUID.randomUUID()`. Analysis of the persisted data confirms all 14 records across F03.00–F03.02 have UUID primary keys, meaning the environment variables (`allocationLotCounter`, `portfolioPositionCounter`, `customerPortfolioCounter`) do not exist in the Joget application.

Current code (problematic):

```java
} catch (Exception e) {
    LogUtil.warn(CLASS_NAME, "ID generation via EnvironmentVariableDao failed, using UUID: " + e.getMessage());
    return UUID.randomUUID().toString();
}
```

**Impact:** All records lack human-readable identifiers. Users cannot reference specific lots, positions, or portfolios by ID. Traceability between forms is reduced to opaque UUIDs.

**Root cause:** Missing Joget environment variables. The code correctly calls the API but the application configuration was never created.

---

### D2: Generated IDs Not Written to Form Field Columns

**File:** `EnrichmentService.java`, lines 1230–1294

The `generateId()` return value is used as the row primary key (the `id` column in `JdbcHelper.insertRow`), but is never written to the corresponding form field column. The form definitions define `IdGeneratorField` columns (`c_lotId`, `c_positionId`, `c_portfolioId`) which remain empty.

Affected insert maps:

| Form | Field map variable | Missing field | Line |
|------|-------------------|---------------|------|
| F03.02 | `lotFields` | `lotId` | 1266–1293 |
| F03.01 | `posFields` | `positionId` | 1245–1258 |
| F03.00 | `pfFields` | `portfolioId` | 1338–1351 |

**Impact:** Even if D1 is fixed and sequential IDs are generated, the `c_lotId`, `c_positionId`, and `c_portfolioId` columns will still be empty. These fields exist in the form definitions for display and querying purposes.

---

### D3: EUR Equivalent Fields Hardcoded Empty

**File:** `EnrichmentService.java`, lines 1253, 1283–1284, 1291

The allocation logic has access to the enrichment record which contains `c_fx_rate_to_eur` (e.g., 0.9168 for USD, 1.0 for EUR). However, all EUR-equivalent fields are written as empty strings:

| Form | Field | Code | Should be |
|------|-------|------|-----------|
| F03.02 | `totalAmountEur` | `lotFields.put("totalAmountEur", "")` | `totalAmount × fxRate` |
| F03.02 | `feeAmountEur` | `lotFields.put("feeAmountEur", "")` | `feeAmount × fxRate` |
| F03.02 | `realizedPnlEur` | `lotFields.put("realizedPnlEur", "")` | `realizedPnl × fxRate` |
| F03.01 | `totalCostBasisEur` | `posFields.put("totalCostBasisEur", "")` | Aggregate from lots |

Observed data: ADVERTA GRUPP has a 35-share MU lot (`totalAmount` = 4970.00 USD). The enrichment record TRX-2BC7DB has `fx_rate_to_eur` = 0.9168. The expected `totalAmountEur` = 4554.46, but the field is empty.

**Impact:** No EUR-denominated reporting is possible. The portfolio form (F03.00) is specified to report in EUR, but all underlying EUR values are missing.

---

### D4: Portfolio Cost Aggregation Mixes Currencies

**File:** `EnrichmentService.java`, lines 1356–1368
**Method:** `sumPositionCosts()`

The portfolio recalculation sums `c_totalCostBasis` from all positions for a customer. However, positions can be in different currencies. ADVERTA GRUPP has:

| Position | Currency | totalCostBasis |
|----------|----------|---------------|
| MU (35 shares) | USD | 4,963.04 |
| INBB055031A (1 unit) | EUR | 931.34 |
| **Portfolio total** | **mixed!** | **5,894.38** |

The portfolio currency is hardcoded to `"EUR"` (line 1346), but the summed value 5,894.38 is a meaningless mix of USD and EUR amounts.

**Fix dependency:** Requires D3 to be fixed first. Once `totalCostBasisEur` is populated on F03.01 positions, the portfolio aggregation should sum that field instead.

---

### D5: Allocation Summary API Returns Minimal Data

**File:** `EnrichmentApiPlugin.java`, lines 1570–1613
**Method:** `handleGetAllocationSummary()`

The `getAllocationSummary` endpoint returns only five fields per lot: `lotId`, `customerId`, `customerName`, `quantity`, `direction`. Missing from the response:

- `totalAmount` — needed to show financial breakdown
- `feeAmount` — needed for fee transparency
- `totalCostWithFees` — the actual cost basis per lot
- `currency` — needed for proper formatting
- `allocationDate` — needed for audit trail
- `pricePerUnit` — needed for verification

**Impact:** The enrichment-workspace UI can only show a basic Customer/Qty/Direction table in the allocation breakdown. The operator has no visibility into the financial details of each allocation without querying the database directly.

---

### D6: snapshotDate Never Populated

**File:** `EnrichmentService.java`, line 1347

The portfolio creation writes `snapshotDate` as an empty string. It is never updated during portfolio recalculation (lines 1360–1368). Per the TRADE-ALLOCATION-SPEC.md section 1.2, `snapshotDate` represents the F03.04 snapshot date used for market values, which is populated by a separate daily snapshot confirmation job, not by the allocation logic.

**Assessment:** This is not a bug in `allocateTrade()`. The field is correctly left empty until a market data snapshot is confirmed. However, the spec should clarify this, and the field should be explicitly documented as populated-by-snapshot-job-only.

---

## 3. Fix Specifications

### Fix 1: Create Joget Environment Variables

**Addresses:** D1
**Type:** Configuration (Joget App)

Create three environment variables in the gamBackOffice Joget application:

| Variable ID | Initial Value | Used by |
|-------------|---------------|---------|
| `allocationLotCounter` | 0 | `LOT-??????` format |
| `portfolioPositionCounter` | 0 | `PP-??????` format |
| `customerPortfolioCounter` | 0 | `CPF-??????` format |

**Location:** Joget App Center → gamBackOffice → Settings → Environment Variables. Each variable must exist before the first allocation. The counter auto-increments on each `getIncrementedValue()` call.

**Fallback improvement:** Optionally, change `generateId()` to throw instead of falling back to UUID, so the error is immediately visible rather than silently producing opaque IDs.

---

### Fix 2: Write Generated ID to Form Field Column

**Addresses:** D2
**Type:** Code change (`EnrichmentService.java`)

After `generateId()` returns the formatted ID, add it to the respective field map before `JdbcHelper.insertRow()`:

F03.02 — allocationLot (after line 1293):
```java
lotFields.put("lotId", lotId);
```

F03.01 — portfolioPosition (in posFields block, after line 1257):
```java
posFields.put("positionId", positionId);
```

F03.00 — customerPortfolio (in pfFields block, after line 1350):
```java
pfFields.put("portfolioId", portfolioId);
```

This ensures both the Joget `id` column (primary key) and the form field column (`c_lotId` etc.) contain the same human-readable ID.

---

### Fix 3: Compute and Persist EUR Equivalents

**Addresses:** D3
**Type:** Code change (`EnrichmentService.java`)

**Step 3a** — Read FX rate from the enrichment record (add after line 1212):

```java
BigDecimal fxRate = parseBigDecimal(enrichment.get("fx_rate_to_eur"));
if (fxRate.compareTo(BigDecimal.ZERO) <= 0) fxRate = BigDecimal.ONE; // fallback for EUR base
```

**Step 3b** — Compute EUR amounts (add after line 1213):

```java
BigDecimal totalAmountEur = totalAmount.multiply(fxRate).setScale(6, RoundingMode.HALF_UP);
BigDecimal feeAmountEur = feeAmount.multiply(fxRate).setScale(6, RoundingMode.HALF_UP);
BigDecimal totalCostWithFeesEur = totalCostWithFees.multiply(fxRate).setScale(6, RoundingMode.HALF_UP);
```

**Step 3c** — Write to lot fields (replace empty strings at lines 1283–1284):

```java
lotFields.put("totalAmountEur", totalAmountEur.toPlainString());
lotFields.put("feeAmountEur", feeAmountEur.toPlainString());
```

**Step 3d** — For SELL lots, compute realizedPnlEur (replace empty string at line 1291):

```java
lotFields.put("realizedPnlEur", realizedPnl.multiply(fxRate).setScale(6, RoundingMode.HALF_UP).toPlainString());
```

**Step 3e** — For F03.01 position creation (line 1253):

```java
posFields.put("totalCostBasisEur", totalCostWithFeesEur.toPlainString());
```

**Step 3f** — For F03.01 position update (around line 1322):

```java
BigDecimal posCostEurBefore = parseBigDecimal(existingPosition.get("totalCostBasisEur"));
BigDecimal newCostEur = isBuy ? posCostEurBefore.add(totalCostWithFeesEur)
                              : posCostEurBefore.subtract(costBasisUsed.multiply(fxRate));
posUpdate.put("totalCostBasisEur", newCostEur.toPlainString());
```

---

### Fix 4: Portfolio Aggregation in EUR

**Addresses:** D4
**Type:** Code change (`EnrichmentService.java`)
**Depends on:** Fix 3

Replace `sumPositionCosts()` usage at line 1358 to sum the EUR field instead.

New helper method:

```java
private BigDecimal sumPositionCostsEur(Connection conn, String positionTable,
                                        String customerId) throws SQLException {
    String sql = "SELECT SUM(" + JdbcHelper.dbCol("totalCostBasisEur") + ") FROM "
        + JdbcHelper.dbTable(positionTable)
        + " WHERE " + JdbcHelper.dbCol("customerId") + " = ?"
        + " AND " + JdbcHelper.dbCol("status") + " = 'active'";
    // ... execute and return
}
```

Then at line 1358:

```java
BigDecimal totalPortfolioCost = sumPositionCostsEur(conn, ac.getPositionTable(), customerId);
```

This ensures the portfolio `totalCostBasis` (which represents EUR per the spec) is actually summed in EUR.

---

### Fix 5: Enrich Allocation Summary API Response

**Addresses:** D5
**Type:** Code change (`EnrichmentApiPlugin.java`)

In `handleGetAllocationSummary()`, extend the lot info map (around line 1590) to include additional fields:

```java
lotInfo.put("totalAmount", parseDbl(lot.get("totalAmount")));
lotInfo.put("feeAmount", parseDbl(lot.get("feeAmount")));
lotInfo.put("totalCostWithFees", parseDbl(lot.get("totalCostWithFees")));
lotInfo.put("currency", lot.get("currency"));
lotInfo.put("allocationDate", lot.get("allocationDate"));
lotInfo.put("pricePerUnit", parseDbl(lot.get("pricePerUnit")));
lotInfo.put("totalAmountEur", parseDbl(lot.get("totalAmountEur")));
lotInfo.put("feeAmountEur", parseDbl(lot.get("feeAmountEur")));
```

Also add portfolio-level summary to the response:

```java
result.put("totalAmount", totalAmount.doubleValue());
result.put("totalFee", totalFee.doubleValue());
result.put("currency", lots.isEmpty() ? "" : lots.get(0).get("currency"));
```

---

## 4. Test Plan

All tests should be added to or extend `EnrichmentServiceAllocateTest.java` using the existing H2 in-memory database infrastructure.

### 4.1 ID Generation Tests

| Test | Assertion |
|------|-----------|
| T1: BUY allocation with env vars present | Lot id = `LOT-000001`, `c_lotId` = `LOT-000001`, position id = `PP-000001`, `c_positionId` = `PP-000001` |
| T2: Second allocation to same customer | Lot id = `LOT-000002`, position id unchanged (`PP-000001`) |
| T3: Allocation to new customer | New portfolio id = `CPF-000002`, `c_portfolioId` = `CPF-000002` |

### 4.2 EUR Conversion Tests

| Test | Assertion |
|------|-----------|
| T4: USD equity BUY (fxRate=0.9168) | `totalAmountEur` = 4970 × 0.9168 = 4554.50, `feeAmountEur` = -6.958 × 0.9168 = -6.38 |
| T5: EUR bond BUY (fxRate=1.0) | `totalAmountEur` = `totalAmount` (no conversion) |
| T6: SELL lot with realized P&L | `realizedPnlEur` = `realizedPnl × fxRate` |
| T7: Position EUR cost updated | `totalCostBasisEur` = sum of lot `totalCostWithFeesEur` |

### 4.3 Portfolio Aggregation Tests

| Test | Assertion |
|------|-----------|
| T8: Mixed-currency portfolio | `totalCostBasis` = sum of positions' `totalCostBasisEur` (all in EUR) |
| T9: Single-currency (EUR only) | `totalCostBasis` = sum of positions' `totalCostBasisEur` = same as local |

### 4.4 API Response Tests

| Test | Assertion |
|------|-----------|
| T10: getAllocationSummary includes financial data | Each lot has `totalAmount`, `feeAmount`, `currency`, `allocationDate` |
| T11: Summary totals | Response includes `totalAmount` and `totalFee` sums |

---

## 5. Implementation Order

The fixes have dependencies and should be implemented in this order:

| Step | Fix | Reason |
|------|-----|--------|
| 1 | Fix 1 — Create Joget env vars | Zero-code prerequisite; unblocks Fix 2 |
| 2 | Fix 2 — Write IDs to field columns | Quick code change; immediately improves traceability |
| 3 | Fix 3 — EUR equivalents | Core data fix; unblocks Fix 4 |
| 4 | Fix 4 — Portfolio EUR aggregation | Depends on Fix 3 |
| 5 | Fix 5 — Enrich API response | Independent; can be done in parallel with Fix 3–4 |

After all fixes, re-run the two test allocations (TRX-2BC7DB and TRX-32EC0C) and verify the data against the expected values in the test plan.

---

## 6. Data Migration

The existing 14 records (4 portfolios, 5 positions, 5 lots) have incorrect data. Two options:

### Option A: Delete and re-allocate (recommended for dev)

Since this is development data with only 2 source transactions, the cleanest approach is to delete all F03.00–F03.02 records, reset the enrichment records' `fund_allocation_status` to empty, reset the environment variable counters, and re-run the allocations through the workspace UI.

### Option B: Backfill via SQL

For production scenarios, write a migration script that reads the existing lot data, computes the missing EUR values from the enrichment FX rates, and updates the records in place. This preserves the allocation history and timestamps.

**Recommendation:** Use Option A for the current development environment. Document Option B as a template for future production migrations.
