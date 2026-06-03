# Customer ID Key Mismatch Fix Spec

**Version:** 1.0
**Date:** 14 March 2026
**Priority:** High — users see raw UUIDs instead of customer names in Joget forms
**Estimated effort:** 0.5 day

---

## 1. Problem

When viewing allocation records in Joget forms (F03.00, F03.01, F03.02), the `customerId` field shows a raw UUID (e.g., `0fae4fde-1678-4bf8-8fcb-4462b8f063ca`) instead of resolving to the customer name via the SelectBox options binder.

### Root cause

There is a key mismatch between what the enrichment-api writes and what the Joget forms expect.

The **customer table** (`app_fd_customer`) has two different identifiers:

| Column | Example value | Role |
|--------|--------------|------|
| `id` (Joget PK) | `0fae4fde-1678-4bf8-8fcb-4462b8f063ca` | Joget internal row ID (UUID) |
| `c_customerId` | `11910225` | Business customer ID |
| `c_customerName` | `ADVERTA GRUPP OÜ` | Display name |

The **forms' SelectBox binders** for customerId use:
```json
"idColumn": "customerId",
"labelColumn": "customerName"
```

This means the SelectBox stores `c_customerId` values (like `11910225`) and displays `c_customerName`. When loading a record, it tries to find a customer where `c_customerId` matches the stored value.

But the **enrichment-api** stores the Joget row UUID (the `id` column) as customerId:

- `handleGetCustomers()` at line 1665 returns `customerId = rs.getString("id")` — the UUID
- The workspace dropdown populates with UUIDs as option values
- `allocateTrade()` receives and stores this UUID into `c_customerId` in F03 tables
- `loadRowByFieldId()` at line 1157 loads customer by primary key `id` — works for the API internally
- But Joget forms can't resolve UUID `0fae4fde-...` against `c_customerId = 11910225`

### Affected forms

| Form | Field | What user sees | What they should see |
|------|-------|---------------|---------------------|
| F03.02 allocationLot | `customerId` (SelectBox) | `0fae4fde-1678-4bf8-8fcb-4462b8f063ca` | `ADVERTA GRUPP OÜ` |
| F03.01 portfolioPosition | `customerId` (SelectBox) | `0fae4fde-1678-4bf8-8fcb-4462b8f063ca` | `ADVERTA GRUPP OÜ` |
| F03.00 customerPortfolio | `customerId` (TextField) | `0fae4fde-1678-4bf8-8fcb-4462b8f063ca` | `11910225` |

Note: F03.01 and F03.00 also have a `customerDisplayName` field that IS correctly populated by the API ("ADVERTA GRUPP OÜ"), so the name is visible — but the `customerId` field itself shows the UUID. F03.02 has NO `customerDisplayName` field, so the customer is only identifiable through the broken SelectBox.

### Assets are NOT affected

The `assetId` field uses the business asset ID (`AST000295`) throughout. The asset master has `c_assetId = AST000295` and the enrichment-api already stores `resolved_asset_id = AST000295`. The form's SelectBox with `idColumn: "assetId"` resolves correctly.

---

## 2. Fix Specification

The fix must change the API to use business `c_customerId` values instead of Joget row UUIDs, while preserving the ability to look up customer records.

### Fix 1: `handleGetCustomers()` — return business customer ID

**File:** `EnrichmentApiPlugin.java`, line 1644–1665
**Change:** Select `c_customerId` alongside `id` and return the business ID.

**Before (line 1644):**
```java
String sql = "SELECT id, " + JdbcHelper.dbCol(ac.getCustomerDisplayNameField())
```

**After:**
```java
String sql = "SELECT id, " + JdbcHelper.dbCol("customerId") + ", "
        + JdbcHelper.dbCol(ac.getCustomerDisplayNameField())
```

**Before (line 1665):**
```java
c.put("customerId", rs.getString("id"));
```

**After:**
```java
c.put("customerId", rs.getString(JdbcHelper.dbCol("customerId")));
```

Also update the search filter (line 1651) to search on `c_customerId` as well:
```java
sql += " AND (" + JdbcHelper.dbCol(ac.getCustomerDisplayNameField())
        + " LIKE ? OR " + JdbcHelper.dbCol("customerId") + " LIKE ?)";
```

### Fix 2: `allocateTrade()` — load customer by business ID

**File:** `EnrichmentService.java`, line 1157

The method currently loads by Joget PK (`loadRowByFieldId`). Since we now receive business customer IDs, load by the `customerId` field instead.

**Before:**
```java
Map<String, String> customer = JdbcHelper.loadRowByFieldId(conn, ac.getCustomerTable(), customerId);
```

**After:**
```java
Map<String, String> customer = JdbcHelper.loadRowByField(conn, ac.getCustomerTable(), "customerId", customerId);
```

No change to what's stored — `customerId` parameter now contains `11910225` instead of the UUID, and this is what gets written to F03 tables at lines 1253, 1277, 1361.

### Fix 3: `handleGetAllocationSummary()` — resolve customer names by business ID

**File:** `EnrichmentApiPlugin.java`, line 1576

Currently uses `loadRowByFieldId` (PK lookup) to resolve customer names. After Fix 2, the lots store business IDs, so this must also change.

**Before:**
```java
Map<String, String> cust = JdbcHelper.loadRowByFieldId(conn, ac.getCustomerTable(), cid);
```

**After:**
```java
Map<String, String> cust = JdbcHelper.loadRowByField(conn, ac.getCustomerTable(), "customerId", cid);
```

### Fix 4: Position and portfolio lookups use `customerId` field — no change needed

The position/portfolio lookups already use:
```java
JdbcHelper.loadRowByFields(conn, ac.getPositionTable(), "customerId", customerId, "assetId", assetId);
JdbcHelper.loadRowByField(conn, ac.getPortfolioTable(), "customerId", customerId);
```

These search by `c_customerId` column in the F03 tables. Since we're now storing business customer IDs there, lookups will work correctly.

The helper methods `countActivePositions()`, `sumPositionCostsEur()` at lines 1491+ also filter by `c_customerId` — they will work correctly with business IDs.

---

## 3. Test Plan

| Test | Expected result |
|------|----------------|
| Open F03.02 allocation lot form for LOT-000001 | `customerId` SelectBox shows "ADVERTA GRUPP OÜ" (not UUID) |
| Open F03.01 portfolio position for PP-000001 | `customerId` SelectBox shows "ADVERTA GRUPP OÜ" |
| Open F03.00 customer portfolio for CPF-000001 | `customerId` TextField shows `11910225` |
| Allocate a new trade via enrichment-workspace | New lot has `c_customerId = 11910225` in DB |
| Existing position found correctly | Second allocation to same customer finds and updates existing position |
| Existing portfolio found correctly | Second allocation to same customer updates existing portfolio |
| Customer dropdown in allocation dialog | Shows customer names, search works by name and by ID |

---

## 4. Summary of Changes

| File | Line(s) | Change |
|------|---------|--------|
| `EnrichmentApiPlugin.java` | 1644–1665 | `handleGetCustomers()` — return `c_customerId` instead of row `id` |
| `EnrichmentApiPlugin.java` | 1576 | `handleGetAllocationSummary()` — resolve customer by `c_customerId` field |
| `EnrichmentService.java` | 1157 | `allocateTrade()` — use `loadRowByField("customerId", ...)` instead of `loadRowByFieldId()` |
