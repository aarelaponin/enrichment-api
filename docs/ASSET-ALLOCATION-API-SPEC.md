# Asset Allocation — enrichment-api Implementation Spec

**Version:** 1.0
**Date:** 2026-03-10
**Scope:** Implementation guide for the enrichment-api plugin to support trade allocation operations (Phase D1).
**Depends on:** TRADE-ALLOCATION-SPEC.md (overall design), CUSTODY-PORTFOLIO-SPEC.md (data model), enrichment-api v1.0 (existing codebase)

---

## 1. Principle

The enrichment-api is the **sole persistence layer** for all enrichment and custody operations. The workspace UI does not know how or where data is stored — it sends intent (e.g., "allocate 400 shares of ENR-00042 to customer C001") and the API handles validation, computation, storage, and cross-table consistency. This spec defines the exact Java changes required to support trade allocation.

---

## 2. Table Registry

All operations reference Joget form tables via `JdbcHelper.dbTable(tableName)` which resolves to `app_fd_{tableName}`. Field columns resolve via `JdbcHelper.dbCol(fieldId)` → `c_{fieldId}`.

| Logical Name | tableName | Form ID | Access Pattern |
|---|---|---|---|
| Enrichment | `trx_enrichment` | `trxEnrichment` | Read + update (allocation status, notes) |
| Secu Transaction | `secu_total_trx` | `secuTotalTransaction` | Read only (qty, price, fee, ticker) |
| Allocation Lot | `allocationLot` | `allocationLot` | Insert + read (sum for allocation summary) |
| Portfolio Position | `portfolioPosition` | `portfolioPosition` | Read + insert + update (upsert per customer×asset) |
| Customer Portfolio | `customerPortfolio` | `customerPortfolio` | Read + insert + update (upsert per customer) |
| Customer | `customerForm` | `customerForm` | Read only (dropdown, validation) |
| Cost Basis Config | `costBasisConfig` | `costBasisConfig` | Read only (method lookup for sells) |
| Position Snapshot | `positionSnapshot` | `dailyPositionSnapshot` | Read only (market prices for portfolio refresh) |

---

## 3. ValidationConfig Extension

### 3.1 New Inner Class: `AllocationConfig`

Add inside `ValidationConfig.java` after the existing `ConfirmationConfig` class:

```java
public static class AllocationConfig {
    // Table names (Joget tableName, not DB table)
    private final String secuTable;          // default: "secu_total_trx"
    private final String lotTable;           // default: "allocationLot"
    private final String positionTable;      // default: "portfolioPosition"
    private final String portfolioTable;     // default: "customerPortfolio"
    private final String customerTable;      // default: "customerForm"
    private final String costBasisTable;     // default: "costBasisConfig"

    // Field mappings — enrichment table (F01.05)
    private final String enrichmentSourceField;      // default: "source_record_id"
    private final String enrichmentAssetField;        // default: "resolved_asset_id"
    private final String enrichmentAssetIsinField;    // default: "asset_isin"
    private final String enrichmentTypeField;         // default: "internal_type"
    private final String enrichmentStatusField;       // default: "status"
    private final String enrichmentAllocStatusField;  // default: "fund_allocation_status"
    private final String enrichmentNotesField;        // default: "processing_notes"
    private final String enrichmentTrxDateField;      // default: "transaction_date"

    // Field mappings — secu transaction table (F01.04)
    private final String secuQuantityField;     // default: "quantity"
    private final String secuPriceField;        // default: "price"
    private final String secuFeeField;          // default: "fee"
    private final String secuAmountField;       // default: "amount"
    private final String secuTickerField;       // default: "ticker"
    private final String secuCurrencyField;     // default: "currency"
    private final String secuEnrichmentLinkField; // default: "enrichment_id"

    // Field mappings — customer table (F10.01)
    private final String customerDisplayNameField;  // default: "displayName"
    private final String customerIsFundField;       // default: "is_fund"

    // Eligible types and statuses
    private final Set<String> eligibleTypes;    // default: {EQ_BUY, EQ_SELL, BOND_BUY, BOND_SELL, SEC_BUY, SEC_SELL}
    private final Set<String> eligibleStatuses; // default: {ENRICHED, IN_REVIEW, ADJUSTED, READY, PAIRED}

    // Tolerance for floating-point quantity comparison
    private final double quantityTolerance;     // default: 0.000001

    // Getters for all fields...

    public boolean isBuyType(String internalType) {
        return internalType != null && (internalType.endsWith("_BUY"));
    }

    public boolean isSellType(String internalType) {
        return internalType != null && (internalType.endsWith("_SELL"));
    }

    public static AllocationConfig parse(JSONObject json) {
        // Parse with defaults for every field
    }
}
```

### 3.2 Root-Level Addition

Add to `ValidationConfig`:

```java
private final AllocationConfig allocation; // nullable

public AllocationConfig getAllocation() {
    return allocation != null ? allocation : AllocationConfig.defaults();
}
```

Parse from config JSON key `"allocation"`:

```java
if (root.has("allocation")) {
    this.allocation = AllocationConfig.parse(root.getJSONObject("allocation"));
} else {
    this.allocation = null; // will use defaults via getter
}
```

### 3.3 Plugin Configuration JSON

The enrichment-api plugin's configuration property should include a new `allocation` section. Example:

```json
{
  "allocation": {
    "secuTable": "secu_total_trx",
    "lotTable": "allocationLot",
    "positionTable": "portfolioPosition",
    "portfolioTable": "customerPortfolio",
    "customerTable": "customerForm",
    "costBasisTable": "costBasisConfig"
  }
}
```

Fields not specified default to the values listed in §3.1. In most deployments, only the table names need to be configured; field names rarely change.

---

## 4. JdbcHelper Extensions

### 4.1 Query by Non-PK Field

Existing `loadRow` and `loadRowByFieldId` only look up by primary key (`id`). Allocation needs lookups like "find secu transaction where enrichment_id = X" or "find position where customerId = X AND assetId = Y".

```java
/**
 * Load a single row matching field = value.
 * Returns null if no match. Throws if multiple matches.
 * @param fieldId Joget field ID (without c_ prefix)
 */
public static Map<String, String> loadRowByField(
        Connection conn, String tableName,
        String fieldId, String fieldValue) throws SQLException {
    String sql = "SELECT * FROM " + dbTable(tableName)
               + " WHERE " + dbCol(fieldId) + " = ? LIMIT 2";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, fieldValue);
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            Map<String, String> row = extractRow(rs);
            if (rs.next()) {
                throw new SQLException("Multiple rows found in " + tableName
                    + " for " + fieldId + "=" + fieldValue);
            }
            return row;
        }
    }
}
```

### 4.2 Query by Two Fields (Composite Lookup)

```java
/**
 * Load a single row matching two field conditions.
 * Returns null if no match.
 */
public static Map<String, String> loadRowByFields(
        Connection conn, String tableName,
        String field1, String value1,
        String field2, String value2) throws SQLException {
    String sql = "SELECT * FROM " + dbTable(tableName)
               + " WHERE " + dbCol(field1) + " = ?"
               + " AND " + dbCol(field2) + " = ? LIMIT 2";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, value1);
        ps.setString(2, value2);
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            Map<String, String> row = extractRow(rs);
            if (rs.next()) {
                throw new SQLException("Multiple rows found in " + tableName
                    + " for " + field1 + "=" + value1 + ", " + field2 + "=" + value2);
            }
            return row;
        }
    }
}
```

### 4.3 Aggregate Query Helper

```java
/**
 * Sum a numeric column with optional WHERE clause.
 * Returns BigDecimal.ZERO if no matching rows.
 */
public static BigDecimal sumColumn(
        Connection conn, String tableName,
        String sumFieldId, String whereFieldId, String whereValue) throws SQLException {
    String sql = "SELECT COALESCE(SUM(CAST(" + dbCol(sumFieldId) + " AS DECIMAL(20,6))), 0)"
               + " FROM " + dbTable(tableName)
               + " WHERE " + dbCol(whereFieldId) + " = ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, whereValue);
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }
}
```

### 4.4 Count Query Helper

```java
/**
 * Count rows matching a WHERE condition.
 */
public static int countRows(
        Connection conn, String tableName,
        String whereFieldId, String whereValue) throws SQLException {
    String sql = "SELECT COUNT(*) FROM " + dbTable(tableName)
               + " WHERE " + dbCol(whereFieldId) + " = ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, whereValue);
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
```

### 4.5 List Query Helper

```java
/**
 * Load all rows matching field = value.
 * Returns empty list if no matches.
 */
public static List<Map<String, String>> loadRowsByField(
        Connection conn, String tableName,
        String fieldId, String fieldValue) throws SQLException {
    String sql = "SELECT * FROM " + dbTable(tableName)
               + " WHERE " + dbCol(fieldId) + " = ?";
    List<Map<String, String>> rows = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, fieldValue);
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(extractRow(rs));
            }
        }
    }
    return rows;
}
```

### 4.6 Private Helper: extractRow

If not already present, add a private helper to convert ResultSet to Map:

```java
private static Map<String, String> extractRow(ResultSet rs) throws SQLException {
    ResultSetMetaData meta = rs.getMetaData();
    Map<String, String> row = new LinkedHashMap<>();
    for (int i = 1; i <= meta.getColumnCount(); i++) {
        row.put(meta.getColumnName(i), rs.getString(i));
    }
    return row;
}
```

**Note:** `loadRowByFieldId` currently strips the `c_` prefix from keys. The new methods should follow the same convention — return field IDs as keys, not DB column names. Add an overload parameter or make it consistent.

---

## 5. ID Generation

### 5.1 Approach

Use Joget's `EnvironmentVariableDao` to maintain counters, matching the format used by `IdGeneratorField` in the forms. This ensures IDs generated by the API are compatible with IDs generated by the forms.

```java
/**
 * Generate a sequential ID matching IdGeneratorField format.
 * E.g., format="LOT-??????", envVariable="allocationLotCounter"
 * → LOT-000001, LOT-000002, ...
 */
public static String generateId(String format, String envVariable) {
    EnvironmentVariableDao envDao = (EnvironmentVariableDao)
        AppUtil.getApplicationContext().getBean("environmentVariableDao");

    // Atomically increment counter
    Long counter = envDao.getIncrementedValue(envVariable);

    // Replace ? chars with zero-padded counter
    int qCount = format.length() - format.replace("?", "").length();
    String padded = String.format("%0" + qCount + "d", counter);
    return format.replace("?".repeat(qCount), padded);
}
```

### 5.2 ID Formats

| Entity | Format | Counter Variable |
|---|---|---|
| Allocation Lot (F03.02) | `LOT-??????` | `allocationLotCounter` |
| Portfolio Position (F03.01) | `PP-??????` | `portfolioPositionCounter` |
| Customer Portfolio (F03.00) | `CPF-??????` | `customerPortfolioCounter` |
| Income Allocation (F03.03) | `IA-??????` | `incomeAllocationCounter` |

Place `generateId()` in `EnrichmentService` or create a dedicated `IdGeneratorHelper` utility class. The counter variables must be pre-created in Joget's Environment Variables settings (or auto-created on first use if the DAO supports it).

---

## 6. EnrichmentApiPlugin.java — Dispatch Changes

### 6.1 Dispatch Order

Add the allocation dispatch keys **after** existing keys in `dispatchSaveParam()`. The specificity-first ordering is maintained because these keys don't conflict with existing ones.

```java
// === Existing dispatchers (lines 965–994) ===
// create, split, merge, delete, statusTransition, confirm, batch, inline-save

// === New: Trade Allocation (D1) ===
if (peek.has("allocateTrade") && peek.has("enrichmentId") && peek.has("customerId")) {
    return handleAllocateTrade(json);
}
if (peek.has("secuTransaction") && peek.has("enrichmentId")) {
    return handleGetSecuTransaction(json);
}
if (peek.has("allocationSummary") && peek.has("enrichmentId")) {
    return handleGetAllocationSummary(json);
}
if (peek.has("customers")) {
    return handleGetCustomers(json);
}
```

### 6.2 Handler: handleGetCustomers

**Purpose:** Return the customer dropdown list. Read-only, no transaction.

```java
private ApiResponse handleGetCustomers(String json) {
    long start = System.currentTimeMillis();
    try {
        JSONObject req = new JSONObject(json);
        String search = req.optString("search", "");
        AllocationConfig ac = config.getAllocation();

        Connection conn = JdbcHelper.getConnection();
        try {
            // Build query
            String sql = "SELECT id, " + JdbcHelper.dbCol(ac.getCustomerDisplayNameField())
                       + " FROM " + JdbcHelper.dbTable(ac.getCustomerTable())
                       + " WHERE (" + JdbcHelper.dbCol(ac.getCustomerIsFundField()) + " IS NULL"
                       + " OR " + JdbcHelper.dbCol(ac.getCustomerIsFundField()) + " != 'yes')";

            if (!search.isEmpty()) {
                sql += " AND (" + JdbcHelper.dbCol(ac.getCustomerDisplayNameField())
                     + " LIKE ? OR id LIKE ?)";
            }
            sql += " ORDER BY " + JdbcHelper.dbCol(ac.getCustomerDisplayNameField());

            PreparedStatement ps = conn.prepareStatement(sql);
            if (!search.isEmpty()) {
                ps.setString(1, "%" + search + "%");
                ps.setString(2, "%" + search + "%");
            }

            ResultSet rs = ps.executeQuery();
            JSONArray customers = new JSONArray();
            while (rs.next()) {
                JSONObject c = new JSONObject();
                c.put("customerId", rs.getString("id"));
                c.put("displayName", rs.getString(
                    JdbcHelper.dbCol(ac.getCustomerDisplayNameField())));
                customers.put(c);
            }
            rs.close();
            ps.close();

            JSONObject result = new JSONObject();
            result.put("customers", customers);
            result.put("ms", System.currentTimeMillis() - start);
            return new ApiResponse(200, result.toString());
        } finally {
            JdbcHelper.closeQuietly(conn);
        }
    } catch (Exception e) {
        return errorResponse(500, "Failed to load customers: " + e.getMessage());
    }
}
```

### 6.3 Handler: handleGetSecuTransaction

**Purpose:** Return secu transaction fields for the allocation dialog's trade summary. Read-only, no transaction.

```java
private ApiResponse handleGetSecuTransaction(String json) {
    long start = System.currentTimeMillis();
    try {
        JSONObject req = new JSONObject(json);
        String enrichmentId = req.getString("enrichmentId");
        AllocationConfig ac = config.getAllocation();

        Connection conn = JdbcHelper.getConnection();
        try {
            // Step 1: Load enrichment record
            Map<String, String> enrichment = JdbcHelper.loadRowByFieldId(
                conn, getTableName(), enrichmentId);
            if (enrichment == null) {
                return errorResponse(404, "Enrichment record not found: " + enrichmentId);
            }

            // Step 2: Get source_record_id
            String sourceId = enrichment.get(ac.getEnrichmentSourceField());
            if (sourceId == null || sourceId.isEmpty()) {
                return errorResponse(400,
                    "Enrichment record has no linked securities transaction");
            }

            // Step 3: Load secu transaction
            Map<String, String> secu = JdbcHelper.loadRowByFieldId(
                conn, ac.getSecuTable(), sourceId);
            if (secu == null) {
                return errorResponse(400,
                    "Securities transaction not found: " + sourceId);
            }

            // Step 4: Build response
            JSONObject result = new JSONObject();
            result.put("enrichmentId", enrichmentId);
            result.put("sourceRecordId", sourceId);
            result.put("ticker", secu.get(ac.getSecuTickerField()));
            result.put("quantity", parseDouble(secu.get(ac.getSecuQuantityField())));
            result.put("price", parseDouble(secu.get(ac.getSecuPriceField())));
            result.put("amount", parseDouble(secu.get(ac.getSecuAmountField())));
            result.put("fee", parseDouble(secu.get(ac.getSecuFeeField())));
            result.put("currency", secu.get(ac.getSecuCurrencyField()));
            result.put("type", enrichment.get(ac.getEnrichmentTypeField()));
            result.put("assetId", enrichment.get(ac.getEnrichmentAssetField()));
            result.put("assetIsin", enrichment.get(ac.getEnrichmentAssetIsinField()));
            result.put("transactionDate", enrichment.get(ac.getEnrichmentTrxDateField()));
            result.put("ms", System.currentTimeMillis() - start);
            return new ApiResponse(200, result.toString());
        } finally {
            JdbcHelper.closeQuietly(conn);
        }
    } catch (Exception e) {
        return errorResponse(500, "Failed to load secu transaction: " + e.getMessage());
    }
}
```

### 6.4 Handler: handleGetAllocationSummary

**Purpose:** Return existing allocation lots for a given enrichment record (how much is already allocated). Read-only.

```java
private ApiResponse handleGetAllocationSummary(String json) {
    long start = System.currentTimeMillis();
    try {
        JSONObject req = new JSONObject(json);
        String enrichmentId = req.getString("enrichmentId");
        AllocationConfig ac = config.getAllocation();

        Connection conn = JdbcHelper.getConnection();
        try {
            // Load all lots for this enrichment
            List<Map<String, String>> lots = JdbcHelper.loadRowsByField(
                conn, ac.getLotTable(), "sourceEnrichmentId", enrichmentId);

            BigDecimal totalQty = BigDecimal.ZERO;
            JSONArray lotArray = new JSONArray();
            for (Map<String, String> lot : lots) {
                BigDecimal qty = parseBigDecimal(lot.get("quantity"));
                totalQty = totalQty.add(qty);

                JSONObject lotJson = new JSONObject();
                lotJson.put("lotId", lot.get("id")); // PK is the lotId
                lotJson.put("customerId", lot.get("customerId"));
                lotJson.put("customerName", lot.get("customerDisplayName"));
                lotJson.put("quantity", qty.doubleValue());
                lotJson.put("direction", lot.get("direction"));
                lotArray.put(lotJson);
            }

            JSONObject result = new JSONObject();
            result.put("enrichmentId", enrichmentId);
            result.put("allocatedQty", totalQty.doubleValue());
            result.put("lotCount", lots.size());
            result.put("lots", lotArray);
            result.put("ms", System.currentTimeMillis() - start);
            return new ApiResponse(200, result.toString());
        } finally {
            JdbcHelper.closeQuietly(conn);
        }
    } catch (Exception e) {
        return errorResponse(500, "Failed to load allocation summary: " + e.getMessage());
    }
}
```

### 6.5 Handler: handleAllocateTrade

**Purpose:** Thin handler that delegates to `EnrichmentService.allocateTrade()`.

```java
private ApiResponse handleAllocateTrade(String json) {
    long start = System.currentTimeMillis();
    try {
        JSONObject req = new JSONObject(json);
        String enrichmentId = req.getString("enrichmentId");
        String customerId = req.getString("customerId");
        BigDecimal quantity = new BigDecimal(req.getString("quantity"));

        Map<String, Object> result = enrichmentService.allocateTrade(
            getTableName(), enrichmentId, customerId, quantity, config);

        result.put("ms", System.currentTimeMillis() - start);
        return new ApiResponse(200, new JSONObject(result).toString());

    } catch (RecordNotFoundException e) {
        return errorResponse(404, e.getMessage());
    } catch (IllegalArgumentException | IllegalStateException e) {
        return errorResponse(400, e.getMessage());
    } catch (Exception e) {
        LogUtil.error(getClassName(), e, "allocateTrade failed");
        return errorResponse(500, "Allocation failed: " + e.getMessage());
    }
}
```

---

## 7. EnrichmentService.java — allocateTrade Method

This is the core transactional operation. It follows the pattern established by `splitRecord()`: load → validate → compute → write (in transaction) → commit → return result.

### 7.1 Method Signature

```java
/**
 * Allocate a portion of a pooled securities trade to a single customer.
 * Creates F03.02 lot, upserts F03.01 position, upserts F03.00 portfolio,
 * updates F01.05 allocation status.
 *
 * @param enrichmentTable The enrichment table name (F01.05)
 * @param enrichmentId    The enrichment record ID to allocate from
 * @param customerId      The target customer ID
 * @param quantity        The quantity to allocate (must be positive)
 * @param config          The validation config with allocation settings
 * @return Map with allocation result fields
 * @throws RecordNotFoundException if enrichment or customer not found
 * @throws IllegalArgumentException for validation failures
 * @throws IllegalStateException for state-related failures
 */
public Map<String, Object> allocateTrade(
        String enrichmentTable,
        String enrichmentId,
        String customerId,
        BigDecimal quantity,
        ValidationConfig config) throws Exception {

    AllocationConfig ac = config.getAllocation();
    Connection conn = null;
    boolean committed = false;

    try {
        conn = JdbcHelper.getConnection();
        conn.setAutoCommit(false);

        // === PHASE 1: LOAD & VALIDATE ===
        // (see §7.2)

        // === PHASE 2: COMPUTE ===
        // (see §7.3)

        // === PHASE 3: WRITE ===
        // (see §7.4)

        conn.commit();
        committed = true;

        // === PHASE 4: BUILD RESPONSE ===
        // (see §7.5)

    } catch (SQLException e) {
        if (conn != null && !committed) {
            try { conn.rollback(); } catch (SQLException re) { /* log */ }
        }
        throw new RuntimeException("Database error during allocation", e);
    } finally {
        if (conn != null) {
            try { conn.setAutoCommit(true); } catch (SQLException e) { /* log */ }
            JdbcHelper.closeQuietly(conn);
        }
    }
}
```

### 7.2 Phase 1: Load & Validate

```java
// V1: Load enrichment record
Map<String, String> enrichment = JdbcHelper.loadRowByFieldId(conn, enrichmentTable, enrichmentId);
if (enrichment == null) {
    throw new RecordNotFoundException(enrichmentId);
}

// V2: Check internal_type
String internalType = enrichment.get(ac.getEnrichmentTypeField());
if (!ac.getEligibleTypes().contains(internalType)) {
    throw new IllegalArgumentException(
        "Record type '" + internalType + "' is not eligible for trade allocation");
}

// V3: Check status
String status = enrichment.get(ac.getEnrichmentStatusField());
if (!ac.getEligibleStatuses().contains(status)) {
    throw new IllegalArgumentException(
        "Record status '" + status + "' is not eligible for allocation");
}

// V4: Check fund_allocation_status
String allocStatus = enrichment.get(ac.getEnrichmentAllocStatusField());
if ("allocated".equals(allocStatus)) {
    throw new IllegalStateException("Trade is already fully allocated");
}

// V5: Load secu transaction
String sourceRecordId = enrichment.get(ac.getEnrichmentSourceField());
if (sourceRecordId == null || sourceRecordId.isEmpty()) {
    throw new IllegalArgumentException("Enrichment has no linked securities transaction");
}
Map<String, String> secu = JdbcHelper.loadRowByFieldId(conn, ac.getSecuTable(), sourceRecordId);
if (secu == null) {
    throw new IllegalArgumentException("Securities transaction not found: " + sourceRecordId);
}
BigDecimal secuQty = parseBigDecimal(secu.get(ac.getSecuQuantityField()));
BigDecimal secuPrice = parseBigDecimal(secu.get(ac.getSecuPriceField()));
BigDecimal secuFee = parseBigDecimal(secu.get(ac.getSecuFeeField()));
String secuCurrency = secu.get(ac.getSecuCurrencyField());
String secuTicker = secu.get(ac.getSecuTickerField());

if (secuQty == null || secuQty.compareTo(BigDecimal.ZERO) <= 0) {
    throw new IllegalArgumentException("Securities transaction has no valid quantity");
}
if (secuPrice == null) {
    throw new IllegalArgumentException("Securities transaction has no valid price");
}

// V5a: Full pairing gate — secu transactions with fees must be fully paired
//       before allocation. An unpaired secu means the bank settlement is not
//       confirmed, so we cannot reliably allocate cost to investors.
String secuPairId = enrichment.get("pair_id");
String secuHasFee = secu.get("has_fee");
if ("yes".equalsIgnoreCase(secuHasFee)) {
    String feeTrxId = enrichment.get("fee_trx_id");
    if (secuPairId == null || secuPairId.isEmpty()
            || feeTrxId == null || feeTrxId.isEmpty()) {
        throw new IllegalArgumentException(
            "Securities transaction requires full pairing (principal + fee) before allocation. "
            + "The fee bank transaction has not yet been matched.");
    }
} else {
    // No fee expected — still require principal pairing for security types
    if (secuPairId == null || secuPairId.isEmpty()) {
        throw new IllegalArgumentException(
            "Securities transaction must be paired with bank settlement before allocation.");
    }
}

// V6: Load and validate customer
Map<String, String> customer = JdbcHelper.loadRowByFieldId(conn, ac.getCustomerTable(), customerId);
if (customer == null) {
    throw new IllegalArgumentException("Customer not found: " + customerId);
}
String isFund = customer.get(ac.getCustomerIsFundField());
if ("yes".equalsIgnoreCase(isFund)) {
    throw new IllegalArgumentException("Cannot allocate to fund entity");
}
String customerName = customer.get(ac.getCustomerDisplayNameField());

// V7: Quantity must be positive
if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
    throw new IllegalArgumentException("Quantity must be positive");
}

// V8: Check remaining unallocated quantity
BigDecimal alreadyAllocated = JdbcHelper.sumColumn(
    conn, ac.getLotTable(), "quantity", "sourceEnrichmentId", enrichmentId);
BigDecimal remaining = secuQty.subtract(alreadyAllocated);
if (quantity.subtract(remaining).doubleValue() > ac.getQuantityTolerance()) {
    throw new IllegalArgumentException(
        "Requested quantity " + quantity + " exceeds remaining " + remaining);
}

// Determine direction
boolean isBuy = ac.isBuyType(internalType);
boolean isSell = ac.isSellType(internalType);
String direction = isBuy ? "BUY" : "SELL";

// V9: For SELL — check customer has sufficient position
Map<String, String> existingPosition = null;
String assetId = enrichment.get(ac.getEnrichmentAssetField());
if (assetId != null) {
    existingPosition = JdbcHelper.loadRowByFields(
        conn, ac.getPositionTable(), "customerId", customerId, "assetId", assetId);
}

if (isSell) {
    if (existingPosition == null) {
        throw new IllegalArgumentException(
            "Customer has no position in this asset — cannot sell");
    }
    BigDecimal positionQty = parseBigDecimal(existingPosition.get("quantity"));
    if (positionQty == null || quantity.compareTo(positionQty) > 0) {
        throw new IllegalArgumentException(
            "Customer has insufficient holdings (" + positionQty + " < " + quantity + ")");
    }
}
```

### 7.3 Phase 2: Compute

```java
// Lot field calculations
BigDecimal totalAmount = quantity.multiply(secuPrice);
BigDecimal feeAmount = secuFee.multiply(quantity).divide(secuQty, 6, RoundingMode.HALF_UP);
BigDecimal totalCostWithFees = totalAmount.add(feeAmount);
String allocationDate = enrichment.get(ac.getEnrichmentTrxDateField());
String assetIsin = enrichment.get(ac.getEnrichmentAssetIsinField());

// For SELL: compute cost basis and realized P&L
BigDecimal costBasisUsed = BigDecimal.ZERO;
BigDecimal realizedPnl = BigDecimal.ZERO;
String costBasisMethod = "AVERAGE"; // default for Phase D1

if (isSell && existingPosition != null) {
    BigDecimal posTotalCost = parseBigDecimal(existingPosition.get("totalCostBasis"));
    BigDecimal posQty = parseBigDecimal(existingPosition.get("quantity"));
    if (posTotalCost != null && posQty != null && posQty.compareTo(BigDecimal.ZERO) > 0) {
        BigDecimal avgCost = posTotalCost.divide(posQty, 6, RoundingMode.HALF_UP);
        costBasisUsed = avgCost.multiply(quantity);
        realizedPnl = totalAmount.subtract(costBasisUsed);
    }
}

// Generate IDs
String lotId = generateId("LOT-??????", "allocationLotCounter");
```

### 7.4 Phase 3: Write (Within Transaction)

```java
String username = getCurrentUser();

// === Step 1: Resolve/Create F03.01 portfolioPosition ===
boolean positionCreated = false;
String positionId;

if (existingPosition != null) {
    positionId = existingPosition.get("id");
} else if (isBuy) {
    // Create new position
    positionCreated = true;
    positionId = generateId("PP-??????", "portfolioPositionCounter");
    Map<String, String> posFields = new LinkedHashMap<>();
    posFields.put("customerId", customerId);
    posFields.put("customerDisplayName", customerName);
    posFields.put("assetId", assetId);
    posFields.put("assetTicker", secuTicker);
    posFields.put("assetIsin", assetIsin != null ? assetIsin : "");
    posFields.put("quantity", "0");
    posFields.put("totalCostBasis", "0");
    posFields.put("totalCostBasisEur", ""); // populated by FX batch
    posFields.put("currency", secuCurrency);
    posFields.put("firstAcquisitionDate", allocationDate);
    posFields.put("lastTransactionDate", allocationDate);
    posFields.put("status", "active");
    JdbcHelper.insertRow(conn, ac.getPositionTable(), positionId, posFields, username);

    // Reload for subsequent calculations
    existingPosition = JdbcHelper.loadRowByFieldId(conn, ac.getPositionTable(), positionId);
} else {
    // SELL without position — already validated in V9, but defensive
    throw new IllegalStateException("Cannot create position for SELL");
}

// === Step 2: Insert F03.02 allocationLot ===
Map<String, String> lotFields = new LinkedHashMap<>();
lotFields.put("sourceEnrichmentId", enrichmentId);
lotFields.put("positionId", positionId);
lotFields.put("customerId", customerId);
// Note: F03.02 does not store customerDisplayName — it's resolved via lookup
lotFields.put("assetId", assetId);
lotFields.put("assetTicker", secuTicker);
lotFields.put("direction", direction);
lotFields.put("quantity", quantity.toPlainString());
lotFields.put("pricePerUnit", secuPrice.toPlainString());
lotFields.put("totalAmount", totalAmount.toPlainString());
lotFields.put("feeAmount", feeAmount.toPlainString());
lotFields.put("totalCostWithFees", totalCostWithFees.toPlainString());
lotFields.put("currency", secuCurrency);
lotFields.put("allocationDate", allocationDate);
lotFields.put("allocationMethod", "MANUAL");
lotFields.put("remainingQuantity", isBuy ? quantity.toPlainString() : "0");
lotFields.put("costBasisMethod", costBasisMethod);
// EUR fields left empty — populated by FX batch
lotFields.put("totalAmountEur", "");
lotFields.put("feeAmountEur", "");
if (isSell) {
    BigDecimal costBasisPerUnit = (existingPosition != null)
        ? parseBigDecimal(existingPosition.get("averageCostPerUnit"))
        : BigDecimal.ZERO;
    lotFields.put("costBasisPerUnit", costBasisPerUnit != null ? costBasisPerUnit.toPlainString() : "0");
    lotFields.put("totalCostBasis", costBasisUsed.toPlainString());
    lotFields.put("realizedPnl", realizedPnl.toPlainString());
    lotFields.put("realizedPnlEur", ""); // populated by FX batch
    lotFields.put("consumedLotIds", ""); // Phase D5 (FIFO/LIFO)
}
JdbcHelper.insertRow(conn, ac.getLotTable(), lotId, lotFields, username);

// === Step 3: Update F03.01 portfolioPosition ===
BigDecimal posQtyBefore = parseBigDecimal(existingPosition.get("quantity"));
BigDecimal posCostBefore = parseBigDecimal(existingPosition.get("totalCostBasis"));
if (posQtyBefore == null) posQtyBefore = BigDecimal.ZERO;
if (posCostBefore == null) posCostBefore = BigDecimal.ZERO;

BigDecimal newQty, newCost;
String newStatus = "active";

if (isBuy) {
    newQty = posQtyBefore.add(quantity);
    newCost = posCostBefore.add(totalCostWithFees);
} else {
    newQty = posQtyBefore.subtract(quantity);
    newCost = posCostBefore.subtract(costBasisUsed);
    if (newQty.compareTo(BigDecimal.ZERO) <= 0) {
        newQty = BigDecimal.ZERO;
        newCost = BigDecimal.ZERO;
        newStatus = "closed";
    }
}

BigDecimal avgCostPerUnit = newQty.compareTo(BigDecimal.ZERO) > 0
    ? newCost.divide(newQty, 6, RoundingMode.HALF_UP)
    : BigDecimal.ZERO;

Map<String, String> posUpdate = new LinkedHashMap<>();
posUpdate.put("quantity", newQty.toPlainString());
posUpdate.put("totalCostBasis", newCost.toPlainString());
posUpdate.put("lastTransactionDate", allocationDate);
posUpdate.put("status", newStatus);
// averageCostPerUnit is a CalculationField in the form, but we store it for JDBC reads
posUpdate.put("averageCostPerUnit", avgCostPerUnit.toPlainString());
JdbcHelper.updateColumns(conn, ac.getPositionTable(), positionId, posUpdate, username);

// === Step 4: Upsert F03.00 customerPortfolio ===
boolean portfolioCreated = false;
String portfolioId;

Map<String, String> existingPortfolio = JdbcHelper.loadRowByField(
    conn, ac.getPortfolioTable(), "customerId", customerId);

if (existingPortfolio == null) {
    // Create new portfolio
    portfolioCreated = true;
    portfolioId = generateId("CPF-??????", "customerPortfolioCounter");
    Map<String, String> pfFields = new LinkedHashMap<>();
    pfFields.put("customerId", customerId);
    pfFields.put("customerDisplayName", customerName);
    pfFields.put("positionCount", "1");
    pfFields.put("totalCostBasis", totalCostWithFees.toPlainString()); // will recalculate below
    pfFields.put("totalMarketValue", ""); // populated by snapshot refresh
    pfFields.put("totalUnrealizedPnl", "");
    pfFields.put("totalRealizedPnl", "0");
    pfFields.put("currency", "EUR");
    pfFields.put("snapshotDate", ""); // populated by snapshot refresh
    pfFields.put("lastRefreshedAt", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        .format(new java.util.Date()));
    pfFields.put("status", "active");
    // Note: portfolioId is passed as the row PK (id parameter of insertRow)
    JdbcHelper.insertRow(conn, ac.getPortfolioTable(), portfolioId, pfFields, username);
} else {
    portfolioId = existingPortfolio.get("id");
}

// Recalculate portfolio aggregates from F03.01 rows
// (always recalculate, even for new portfolio, to handle edge cases)
int activePositions = countActivePositions(conn, ac.getPositionTable(), customerId);
BigDecimal totalPortfolioCost = sumPositionCosts(conn, ac.getPositionTable(), customerId);

Map<String, String> pfUpdate = new LinkedHashMap<>();
pfUpdate.put("positionCount", String.valueOf(activePositions));
pfUpdate.put("totalCostBasis", totalPortfolioCost.toPlainString());
pfUpdate.put("lastRefreshedAt", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    .format(new java.util.Date()));
if (activePositions == 0) {
    pfUpdate.put("status", "closed");
}
JdbcHelper.updateColumns(conn, ac.getPortfolioTable(), portfolioId, pfUpdate, username);

// === Step 5: Update F01.05 enrichment record ===
BigDecimal newAllocatedQty = alreadyAllocated.add(quantity);
String newAllocStatus;
if (newAllocatedQty.subtract(secuQty).abs().doubleValue() <= ac.getQuantityTolerance()) {
    newAllocStatus = "allocated";
} else {
    newAllocStatus = "partially_allocated";
}

String existingNotes = enrichment.get(ac.getEnrichmentNotesField());
String noteAppend = "Allocated " + quantity.toPlainString() + " shares to "
    + customerName + " (lot " + lotId + ")";
String newNotes = (existingNotes != null && !existingNotes.isEmpty())
    ? existingNotes + "\n" + noteAppend : noteAppend;

Map<String, String> enrichUpdate = new LinkedHashMap<>();
enrichUpdate.put(ac.getEnrichmentAllocStatusField(), newAllocStatus);
enrichUpdate.put(ac.getEnrichmentNotesField(), newNotes);
JdbcHelper.updateColumns(conn, enrichmentTable, enrichmentId, enrichUpdate, username);
```

### 7.5 Phase 4: Build Response

```java
Map<String, Object> result = new LinkedHashMap<>();
result.put("success", true);
result.put("lotId", lotId);
result.put("positionId", positionId);
result.put("positionCreated", positionCreated);
result.put("portfolioId", portfolioId);
result.put("portfolioCreated", portfolioCreated);
result.put("direction", direction);
result.put("quantity", quantity.doubleValue());
result.put("totalAmount", totalAmount.doubleValue());
result.put("feeAmount", feeAmount.doubleValue());
result.put("totalCostWithFees", totalCostWithFees.doubleValue());
result.put("currency", secuCurrency);
result.put("allocationStatus", newAllocStatus);
result.put("remainingQty", secuQty.subtract(newAllocatedQty).doubleValue());
if (isSell) {
    result.put("costBasisUsed", costBasisUsed.doubleValue());
    result.put("realizedPnl", realizedPnl.doubleValue());
    result.put("costBasisMethod", costBasisMethod);
}
return result;
```

### 7.6 Private Helpers

```java
/**
 * Count active F03.01 positions for a customer.
 */
private int countActivePositions(Connection conn, String positionTable, String customerId)
        throws SQLException {
    String sql = "SELECT COUNT(*) FROM " + JdbcHelper.dbTable(positionTable)
               + " WHERE " + JdbcHelper.dbCol("customerId") + " = ?"
               + " AND " + JdbcHelper.dbCol("status") + " = 'active'";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, customerId);
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}

/**
 * Sum totalCostBasis across active F03.01 positions for a customer.
 * Note: This returns local-currency costs. EUR conversion is handled separately.
 */
private BigDecimal sumPositionCosts(Connection conn, String positionTable, String customerId)
        throws SQLException {
    String sql = "SELECT COALESCE(SUM(CAST("
               + JdbcHelper.dbCol("totalCostBasis") + " AS DECIMAL(20,6))), 0)"
               + " FROM " + JdbcHelper.dbTable(positionTable)
               + " WHERE " + JdbcHelper.dbCol("customerId") + " = ?"
               + " AND " + JdbcHelper.dbCol("status") + " = 'active'";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, customerId);
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }
}
```

---

## 8. Error Handling

### 8.1 Exception Mapping

| Exception | HTTP | When |
|---|---|---|
| `RecordNotFoundException` | 404 | Enrichment record not found |
| `IllegalArgumentException` | 400 | Validation failure (type, status, quantity, customer) |
| `IllegalStateException` | 400 | State conflict (already allocated, no position for sell) |
| `VersionConflictException` | 409 | Concurrent modification (if optimistic locking added) |
| `SQLException` | 500 | Database error (auto-rollback) |
| `RuntimeException` | 500 | Unexpected errors |

### 8.2 Transaction Safety

The entire `allocateTrade` operation runs in a single JDBC transaction. If any step fails:

1. The `catch` block calls `conn.rollback()`.
2. No partial writes survive — F03.02 lot, F03.01 position, F03.00 portfolio, and F01.05 status update are atomic.
3. The exception propagates to the handler, which maps it to an appropriate HTTP error response.

### 8.3 Concurrency Considerations

If two operators simultaneously allocate from the same enrichment record:

- Both read the same `alreadyAllocated` sum.
- Both may pass the V8 validation (remaining qty check).
- Both attempt to insert lots and update the enrichment.
- **Risk:** Over-allocation (total lots exceed secu transaction quantity).

**Mitigation options (choose one):**

1. **Row-level lock:** `SELECT ... FOR UPDATE` on the enrichment record at the start of the transaction. This serializes concurrent allocations for the same trade.
2. **Post-write check:** After inserting the lot, re-sum allocated quantity. If it exceeds secu quantity, rollback and return 409.
3. **Optimistic check:** Add a version field to the enrichment record. Each allocation increments it. The update uses `WHERE version = expected`.

**Recommendation:** Option 1 (row-level lock) is simplest and most reliable. Add to Phase 1:

```java
// After loading enrichment, acquire row lock
String lockSql = "SELECT id FROM " + JdbcHelper.dbTable(enrichmentTable)
    + " WHERE id = ? FOR UPDATE";
try (PreparedStatement ps = conn.prepareStatement(lockSql)) {
    ps.setString(1, enrichmentId);
    ps.executeQuery();
}
```

---

## 9. New Exception Classes

No new exception classes needed. The existing exceptions (`RecordNotFoundException`, `IllegalArgumentException`, `IllegalStateException`) cover all cases.

If desired for clarity, a dedicated `AllocationException` could be added:

```java
public class AllocationException extends RuntimeException {
    private final String enrichmentId;
    private final String reason;

    public AllocationException(String enrichmentId, String reason) {
        super("Allocation failed for " + enrichmentId + ": " + reason);
        this.enrichmentId = enrichmentId;
        this.reason = reason;
    }
    // getters
}
```

This is optional. The standard exceptions are sufficient.

---

## 10. Files to Modify — Summary

| File | Change | Size Estimate |
|---|---|---|
| `ValidationConfig.java` | Add `AllocationConfig` inner class (~100 lines), parse from JSON, add getter | +120 lines |
| `JdbcHelper.java` | Add `loadRowByField`, `loadRowByFields`, `sumColumn`, `countRows`, `loadRowsByField`, `extractRow` | +80 lines |
| `EnrichmentService.java` | Add `allocateTrade()` method with all phases, `countActivePositions()`, `sumPositionCosts()`, `generateId()` | +250 lines |
| `EnrichmentApiPlugin.java` | Add 4 dispatch keys in `dispatchSaveParam()`, add `handleAllocateTrade()`, `handleGetSecuTransaction()`, `handleGetAllocationSummary()`, `handleGetCustomers()` | +200 lines |

**Total estimated new code:** ~650 lines across 4 files.

---

## 11. Implementation Order

| Step | File | What | Dependencies |
|---|---|---|---|
| 1 | `ValidationConfig.java` | Add `AllocationConfig` inner class with defaults and parser | None |
| 2 | `JdbcHelper.java` | Add query helpers (`loadRowByField`, `loadRowByFields`, `sumColumn`, `countRows`, `loadRowsByField`) | None |
| 3 | `EnrichmentService.java` | Add `generateId()` helper method | None |
| 4 | `EnrichmentApiPlugin.java` | Add `handleGetCustomers()` dispatch + handler | Steps 1–2 |
| 5 | `EnrichmentApiPlugin.java` | Add `handleGetSecuTransaction()` dispatch + handler | Steps 1–2 |
| 6 | `EnrichmentApiPlugin.java` | Add `handleGetAllocationSummary()` dispatch + handler | Steps 1–2 |
| 7 | `EnrichmentService.java` | Add `allocateTrade()` — full method | Steps 1–3 |
| 8 | `EnrichmentApiPlugin.java` | Add `handleAllocateTrade()` dispatch + handler | Step 7 |
| 9 | Plugin config | Update plugin JSON property to include `allocation` section | Step 1 |

Steps 1–3 can be done in parallel. Steps 4–6 can be done in parallel. Steps 7–8 are sequential.

---

## 12. Testing Checklist

| # | Test Case | Expected Result |
|---|---|---|
| T1 | `getCustomers` with no search | All non-fund customers returned |
| T2 | `getCustomers` with search="Anna" | Filtered results |
| T3 | `getSecuTransaction` for valid enrichment | Secu fields returned |
| T4 | `getSecuTransaction` for enrichment without source | 400 error |
| T5 | `getAllocationSummary` for enrichment with no lots | `allocatedQty: 0, lots: []` |
| T6 | `getAllocationSummary` for enrichment with 2 lots | Correct sum and lot details |
| T7 | BUY allocation — first lot for new customer×asset | Position created, portfolio created |
| T8 | BUY allocation — second lot for same customer×asset | Position updated, portfolio updated |
| T9 | BUY allocation — full quantity allocated | `fund_allocation_status = allocated` |
| T10 | Partial allocation | `fund_allocation_status = partially_allocated` |
| T11 | Over-allocation attempt | 400 error with remaining qty |
| T12 | Allocation to fund entity | 400 error |
| T13 | Allocation for wrong enrichment type | 400 error |
| T14 | Allocation for wrong enrichment status | 400 error |
| T15 | Allocation for already-allocated trade | 400 error |
| T16 | SELL allocation with sufficient position | Lot created, position reduced, P&L calculated |
| T17 | SELL allocation without position | 400 error |
| T18 | SELL allocation exceeding position | 400 error |
| T19 | SELL that closes position (qty → 0) | Position status = closed |
| T20 | Transaction rollback on DB error | No partial writes |
| T21 | Concurrent allocation (2 operators) | Row lock prevents over-allocation |

---

## 13. Future Phases (Out of Scope)

| Phase | Feature | Notes |
|---|---|---|
| D2 | Income allocation (dividends, interest) | Similar pattern but uses share-days calculation via F03.03 |
| D5 | FIFO/LIFO cost basis | Requires consuming specific BUY lots by date order, updating `remainingQuantity` |
| D6 | Batch/proportional allocation | Allocate to multiple customers in one operation based on order book |
| D7 | FX conversion batch | Populate EUR fields on lots and positions based on exchange rates |
| D8 | Daily snapshot refresh | Recalculate F03.00 market values from F03.04 prices |
