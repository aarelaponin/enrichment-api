# Enrichment API — Remaining Work

**Date**: 2026-03-02
**Based on**: enrichment-api-review.md cross-checked against current implementation

---

## Open Review Findings

These items from the code review have **not yet been fixed**.

### ~~M1 — REMOVED~~

The table name is a runtime plugin property — the admin configures it. The default value in the JSON is just a placeholder hint, not a contract. There is nothing to "align" — this is working as designed per the no-hardcoding principle.

### M2 — Java 11 target instead of 17

**File**: `pom.xml` lines 16-17 and line 41
**Problem**: `<maven.compiler.source>11</maven.compiler.source>` and `<release>11</release>`. Platform runs Java 17.
**Action**: Change to 17. Enables text blocks, switch expressions, records.
**Note**: statement-importer also targets Java 11 — if you change this, consider aligning both.

### M4 — validationConfig parsed on every request

**File**: `EnrichmentApiPlugin.java` method `getConfidenceOverrides()`
**Problem**: Parses the `validationConfig` JSON string on every `updateRecord()` call.
**Action**: Parse once at initialization (or on first use) and cache. Invalidate only if plugin properties change.

### M6 — FormDataDao null form-definition parameter

**File**: `EnrichmentApiPlugin.java` lines 232-234, `EnrichmentService.java` lines 39, 74
**Problem**: Passes `null` as first argument to `dao.count()`, `dao.find()`, `dao.load()`. Joget may need a form definition for correct table resolution.
**Action**: Verify this works in Joget 8.1.6 with a runtime test. If it doesn't, pass the form ID from plugin properties instead.

### M7 — Missing plugin properties

**File**: `src/main/resources/properties/EnrichmentApiPlugin.json`
**Problem**: `maxPageSize` and `enableBatchOperations` properties defined in spec §2.1 are not in the properties JSON.
**Action**: Add both properties. Use `maxPageSize` in the records endpoint instead of hardcoded 200. Use `enableBatchOperations` as a guard for split/merge/confirm endpoints once implemented.

### M8 — Health endpoint missing uptime_ms

**File**: `EnrichmentApiPlugin.java` method `health()`
**Problem**: `timestamp` was added but `uptime_ms` is still missing per spec §5.1.
**Action**: Track plugin start time (e.g., in `Activator.start()` or a static field) and compute `System.currentTimeMillis() - startTime`.

---

## Missing Endpoints (8 of 12)

Ordered by recommended implementation sequence.

### 1. POST /records/{id}/status — Single Status Transition

**Spec**: §5.5
**Priority**: Highest — unlocks all UI status change buttons (Mark Ready, Return to Workspace, Reprocess, Begin Review).

**What to implement**:

- New `@Operation` method in `EnrichmentApiPlugin` for path `/records/{id}/status`, method POST
- Parse JSON body: `{"targetStatus": "ready", "reason": "Analyst marked as ready"}`
- Resolve `targetStatus` to `Status` enum via `Status.fromCode()`
- Call `StatusManager.transition(dao, tableName, EntityType.ENRICHMENT, id, targetStatus, "enrichment-api", reason)`
- Catch `InvalidTransitionException` → 400 with `validTransitions` from `StatusManager.getValidTransitions()`
- Return 200: `{"id": "...", "previousStatus": "...", "newStatus": "...", "modifiedBy": "...", "ms": N}`

**Service method needed**: `transitionStatus(tableName, id, targetStatus, reason)` in `EnrichmentService`

### 2. POST /records/status — Batch Status Transition

**Spec**: §5.6
**Priority**: High — unlocks batch actions in datalist (Mark Ready batch, Return to Workspace batch).

**What to implement**:

- New `@Operation` method for path `/records/status`, method POST
- Parse JSON body: `{"recordIds": ["ENR-042", "ENR-043"], "targetStatus": "ready"}`
- Iterate each record, calling `StatusManager.transition()` individually
- Collect `succeeded` and `failed` arrays — failed records do NOT block others
- Return 200: `{"succeeded": [...], "failed": [...], "ms": N}`

**Service method needed**: `batchTransitionStatus(tableName, recordIds, targetStatus)` in `EnrichmentService`

### 3. DELETE /records/{id}

**Spec**: §5.7
**Priority**: Medium — completes CRUD.

**What to implement**:

- New `@Operation` method for path `/records/{id}`, method DELETE
- Load record, check status ∈ {`new`, `error`, `manual_review`} — otherwise return 400
- Soft-delete (set `c_deleted = 1`) or hard-delete via `dao.delete()`
- Return 204 No Content

**Service method needed**: `deleteRecord(tableName, id)` in `EnrichmentService`

### 4. GET /summary

**Spec**: §5.12
**Priority**: Medium — useful for dashboard view.

**What to implement**:

- New `@Operation` method for path `/summary`, method GET
- Accept `statementId` query parameter
- Run aggregate query: GROUP BY status, currency → count, sum(total_amount), min/max date
- Return JSON array of summary rows

**Service method needed**: `getSummary(tableName, statementId)` — may need direct JDBC for GROUP BY since FormDataDao doesn't support aggregation natively.

### 5. GET /reconciliation/{statementId}

**Spec**: §5.11
**Priority**: High (needed before confirm) — powers the reconciliation panel in the confirmation dialog.

**What to implement**:

- New `@Operation` method for path `/reconciliation/{statementId}`, method GET
- Query F01.03 + F01.04 source tables for input totals (per currency)
- Query F01.05 for: manual adjustments, confirmed records, active records (per currency)
- Compute per-currency: source_input, manual_adj, adjusted_input, output, remaining, discrepancy
- Apply tolerance from `validationConfig.reconciliation.tolerance`
- Return per-currency reconciliation rows with status indicators (balanced/warning/blocked)

**Service method needed**: `getReconciliation(tableName, statementId, validationConfig)` — requires direct JDBC for cross-table aggregation.

**Config dependency**: Requires `validationConfig.reconciliation` section to be fully parsed (source table names, amount/currency/statement field mappings, tolerance values).

### 6. POST /records/confirm

**Spec**: §5.8
**Priority**: Highest business value — the central operation.

**What to implement**:

- New `@Operation` method for path `/records/confirm`, method POST
- Parse JSON body: `{"recordIds": ["ENR-001", "ENR-002"], "userId": "analyst1"}`
- Filter to only `status = ready` records — silently skip others
- Validate each record against `validationConfig.requiredFields` and `conditionalRequirements`
- Run reconciliation check (reuse reconciliation service)
- Determine if this is a "final confirmation" (no active records remain after this batch)
- If final + discrepancy exceeds tolerance → return 400 (blocked)
- Use JDBC transaction for atomicity: transition all records to CONFIRMED via `StatusManager`, set `confirmed_by` and `confirmed_at` fields
- Return 200: `{"status": "success", "confirmedRecordIds": [...], "reconciliation": {...}}`

**Service method needed**: `confirmRecords(tableName, recordIds, userId, validationConfig)` — requires JDBC transaction (spec §3.4).

**Config dependency**: Requires full `validationConfig` parsing: `requiredFields`, `conditionalRequirements`, `reconciliation`, `confirmation` sections.

### 7. POST /records/{id}/split

**Spec**: §5.9
**Priority**: Medium — complex operation.

**What to implement**:

- New `@Operation` method for path `/records/{id}/split`, method POST
- Parse JSON body: splits array with per-child allocations (amount, customer, type, etc.)
- Validate: sum of child amounts = parent amount (within tolerance)
- Validate: parent status allows splitting (enriched, in_review, adjusted)
- JDBC transaction:
  - Create N child records with `origin = split`, `parent_enrichment_id`, `group_id`, `split_sequence`
  - Transition parent to SUPERSEDED via `StatusManager`
  - Transition children to ENRICHED via `StatusManager`
- Return 201: `{"parentId": "...", "childIds": [...], "groupId": "..."}`

**Service method needed**: `splitRecord(tableName, id, splits, validationConfig)` — requires JDBC transaction.

**Config dependency**: Requires `validationConfig.splitMerge` section for field ID mappings.

### 8. POST /records/merge

**Spec**: §5.10
**Priority**: Medium — complex operation.

**What to implement**:

- New `@Operation` method for path `/records/merge`, method POST
- Parse JSON body: `{"sourceRecordIds": [...], "mergedRecord": {field overrides}}`
- Validate: all sources in same statement, same currency
- Validate: all source statuses allow merging (enriched, in_review, adjusted, ready)
- JDBC transaction:
  - Create 1 merged record with `origin = merge`, `group_id`, summed amounts
  - Apply field overrides from request body (type, customer, description, FX rate)
  - Transition all source records to SUPERSEDED via `StatusManager`
  - Transition merged record to ENRICHED via `StatusManager`
- Return 201: `{"mergedRecordId": "...", "sourceRecordIds": [...], "groupId": "..."}`

**Service method needed**: `mergeRecords(tableName, sourceIds, mergedFields, validationConfig)` — requires JDBC transaction.

**Config dependency**: Requires `validationConfig.splitMerge` section for field ID mappings.

---

## validationConfig Parsing (Prerequisite for endpoints 5-8)

The current implementation only parses `confidenceOverrides` from validationConfig. Endpoints 5-8 require full parsing of the spec §2.2 schema:

| Sub-key | Needed by | What it contains |
|---|---|---|
| `requiredFields` | confirm | List of field IDs that must be non-empty for confirmation |
| `conditionalRequirements` | confirm | Array of condition→requiredFields rules |
| `reconciliation` | reconciliation, confirm | Source table config, amount/currency/statement field mappings, tolerance |
| `splitMerge` | split, merge | Field ID mappings for amounts, fees, FX, customer, lineage fields |
| `confirmation` | confirm | `confirmedByField`, `confirmedAtField` audit field mappings |

**Action**: Create a `ValidationConfig` class that parses the full JSON at initialization and exposes typed accessors. Replace the current `getConfidenceOverrides()` with this.

---

## JDBC Transaction Infrastructure (Prerequisite for endpoints 6-8)

Spec §3.4 requires direct JDBC with explicit transaction control for confirm, split, and merge (all-or-nothing semantics). The current codebase uses only `FormDataDao`.

**Action**: Add a utility method to obtain a JDBC `Connection` from the Joget `DataSource` (via `AppUtil.getApplicationContext().getBean("setupDataSource")` or `workflowManager.getConnection()`). Implement try/commit/rollback pattern as shown in spec §3.4.

---

## Summary

| Category | Count |
|---|---|
| Open review findings (M2, M4, M6, M7, M8) | 5 |
| Missing endpoints | 8 |
| Infrastructure prerequisites | 2 (validationConfig parser, JDBC transactions) |
| **Total work items** | **15** |
