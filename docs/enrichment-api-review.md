# Enrichment API — Implementation Review

**Date**: 2026-03-02
**Scope**: `enrichment-api` plugin source code vs. `enrichment-api-specification.md`
**Verdict**: Implementation is at **~20% of spec** — only 4 of 12 endpoints exist. Foundational architecture is sound but has 3 critical issues and several medium-priority items that should be addressed before building out the remaining endpoints.

---

## 1. Completeness vs. Specification

### 1.1 Endpoint Coverage

| # | Spec Endpoint | HTTP | Implemented? | Notes |
|---|---|---|---|---|
| 1 | GET /health | GET | **YES** | Works, but missing `timestamp` and `uptime_ms` fields |
| 2 | GET /records | GET | **YES** | Core pagination+filter+search. Working. |
| 3 | GET /records/{id} | GET | **YES** | Working. |
| 4 | PUT /records/{id} | PUT | **YES** | Working with optimistic locking + auto-transition. |
| 5 | POST /records/{id}/status | POST | **NO** | Not implemented — single status transition |
| 6 | POST /records/status | POST | **NO** | Not implemented — batch status transition |
| 7 | DELETE /records/{id} | DELETE | **NO** | Not implemented |
| 8 | POST /records/confirm | POST | **NO** | Not implemented — the central business operation |
| 9 | POST /records/{id}/split | POST | **NO** | Not implemented |
| 10 | POST /records/merge | POST | **NO** | Not implemented |
| 11 | GET /reconciliation/{statementId} | GET | **NO** | Not implemented |
| 12 | GET /summary | GET | **NO** | Not implemented |

**Result: 4 of 12 endpoints implemented (33% by count, ~20% by business value** since the high-value operations — confirm, split, merge, reconciliation — are all missing).

### 1.2 Configuration Coverage

| Spec Property | In Properties JSON? | Used in Code? |
|---|---|---|
| tableName | YES | YES |
| defaultSort | YES | YES |
| validationConfig | YES (as textarea) | PARTIAL — only `confidenceOverrides` sub-key is parsed |
| maxPageSize | NO | Hardcoded as 200 |
| enableBatchOperations | NO | N/A (no batch operations yet) |

**Missing from validationConfig parsing**: `requiredFields`, `conditionalRequirements`, `reconciliation`, `splitMerge`, `confirmation` — all sub-keys defined in spec §2.2 are not parsed. Only `confidenceOverrides` (which isn't even in the spec's validationConfig schema) is used.

### 1.3 Missing Service Layer Methods

The `EnrichmentService` class currently has only 2 public methods:

- `loadRecord()` — used by GET /records/{id}
- `updateRecord()` — used by PUT /records/{id}

The following are needed per spec but don't exist:

- `transitionStatus(tableName, id, targetStatus, reason)` — for POST /records/{id}/status
- `batchTransitionStatus(tableName, recordIds, targetStatus)` — for POST /records/status
- `deleteRecord(tableName, id)` — for DELETE /records/{id}
- `confirmRecords(tableName, recordIds, userId)` — for POST /records/confirm
- `splitRecord(tableName, id, splits)` — for POST /records/{id}/split
- `mergeRecords(tableName, sourceIds, mergedFields)` — for POST /records/merge
- `getReconciliation(statementId)` — for GET /reconciliation/{statementId}
- `getSummary(statementId)` — for GET /summary

---

## 2. Architecture Review

### 2.1 gam-framework Integration — PARTIALLY CORRECT, ONE CRITICAL ISSUE

**What's good**: The `EnrichmentService` imports and instantiates `StatusManager`, uses `canTransition()` for validation, and creates `TransitionAuditEntry` for audit logging. The `pom.xml` correctly declares the `gam-framework` dependency.

**Critical issue — bypasses StatusManager.transition()**:

In `EnrichmentService.updateRecord()` lines 111-118, the auto-transition from ENRICHED → ADJUSTED is implemented as:

```java
if (statusManager.canTransition(EntityType.ENRICHMENT, Status.ENRICHED, Status.ADJUSTED)) {
    row.setProperty("status", Status.ADJUSTED.getCode());  // ← DIRECT SET!
    autoTransitioned = true;
}
```

This directly sets the status string on the row, then writes its own audit entry manually (lines 131-139). This **violates spec §3.5 Rule #1**: "Never set status directly. Always use `StatusManager.transition()`."

The correct approach should call `statusManager.transition(dao, EntityType.ENRICHMENT, id, Status.ADJUSTED, "enrichment-api", "Auto-transition on inline edit")` which handles the status write + audit in one atomic operation.

The current implementation creates a subtle inconsistency: the `StatusManager.transition()` method calls `dao.saveOrUpdate()` itself to persist the status change, while `EnrichmentService.updateRecord()` does a separate `dao.saveOrUpdate()` for the field changes + status change combined. If you switch to `StatusManager.transition()`, you'll need to decide whether the field update and status update happen in one save or two — this is an architectural decision to resolve.

### 2.2 OSGi Bundle Configuration — OK

The `pom.xml` embeds gam-framework inside the OSGi bundle via `Embed-Dependency` with compile scope. This matches the established project pattern — the statement-importer plugin does the same thing (`*;scope=compile|runtime;inline=false` with gam-framework as a compile dependency). Each plugin carries its own copy of the framework classes inside the bundle JAR.

**Note**: The API spec §3.5 incorrectly states that gam-framework is "deployed to `{JOGET_HOME}/wflow/lib/` (shared classpath), not bundled inside the plugin OSGi JAR." The spec should be updated to match the actual project pattern of embedding within each bundle.

### 2.3 Java Version Mismatch

The `pom.xml` specifies Java 11 (`<maven.compiler.source>11</maven.compiler.source>`), but the spec says the platform runs on Java 17. While Java 11 bytecode runs fine on Java 17, there's no reason not to target 17 — it enables records, sealed classes, switch expressions, and text blocks which would improve code readability (especially for building JSON/HQL strings).

### 2.4 Default Table Name Mismatch

The plugin properties JSON file sets the default `tableName` to `trxEnrichment`, but the API spec §2.1 says the default should be `trx_enrichment`. The spec uses snake_case table names consistently (matching the `EntityType.ENRICHMENT.getTableName()` pattern from gam-framework).

---

## 3. Code Quality Review

### 3.1 EnrichmentApiPlugin.java

**Strengths:**
- Clean separation of concerns — plugin class handles HTTP routing, delegates to service
- Dynamic field iteration via `rowToMap()` follows spec §3.2 exactly
- Good error handling with typed exceptions (404, 409, 400, 500)
- Performance timing (`ms` field) on all responses
- Proper parameter validation and defaults

**Issues:**

**(a) HQL injection vulnerability (HIGH)**

Lines 192-193:
```java
cond.append("e.customProperties.").append(kv[0].trim()).append(" = ?");
```

The field name `kv[0]` comes directly from user input and is interpolated into the HQL string without validation. While the value is parameterized (`?`), the **column name** is not — it's concatenated directly. An attacker could inject: `filter=status) OR 1=1 OR (status=bank` to manipulate the HQL WHERE clause.

**Fix**: Validate that field names match a safe pattern (e.g., `^[a-zA-Z_][a-zA-Z0-9_]*$`) before appending to the HQL string. The same issue exists for the `search` parameter field name (line 202) and `sort` parameter (line 175).

**(b) FormDataDao API usage may be incorrect**

Lines 214-216:
```java
long total = dao.count(null, tableName, condition, paramsArr);
FormRowSet rows = dao.find(null, tableName, condition, paramsArr, sortField, sortDesc, start, ps);
```

The first parameter is `null` (form definition). In Joget's `FormDataDao`, passing `null` for the form definition means the DAO won't resolve the correct table mapping. Depending on the Joget version, this may work or silently return no results. The spec §3.1 mentions using `FormDataDao` but the actual method signatures in Joget 8.1.6 may differ — verify against the `FormDataDao` interface.

**(c) `new EnrichmentService()` per request (MEDIUM)**

Line 394-396:
```java
private EnrichmentService getService() {
    return new EnrichmentService();
}
```

The comment says "Spring context (FormDataDao) is only available at request time" — but `EnrichmentService` doesn't hold any Spring beans as fields. It calls `getDao()` which does `AppUtil.getApplicationContext().getBean("formDataDao")` every time. Creating a new `StatusManager` instance per request (line 32 of `EnrichmentService`) is wasteful — `StatusManager` is stateless and thread-safe (its `TRANSITIONS` map is static and immutable). Consider making `EnrichmentService` a singleton or at least caching it.

**(d) Missing `WHERE` keyword handling**

Line 191 builds `WHERE` manually:
```java
cond.append(cond.length() == 0 ? "WHERE " : " AND ");
```

But Joget's `FormDataDao.find()` with a condition parameter typically expects the condition **without** the `WHERE` keyword — the DAO prepends it internally. Check whether the generated HQL `WHERE e.customProperties.status = ?` actually works, or if it should be just `e.customProperties.status = ?`.

**(e) `rowToMap()` emits empty strings instead of null**

Line 448:
```java
m.put(fieldId, val != null ? val : "");
```

The spec §3.1 says: "JSON serialization converts both to null." But the code converts null to empty string `""`. This inconsistency could break UI filtering logic that checks for null vs empty.

### 3.2 EnrichmentService.java

**Strengths:**
- Clean optimistic locking implementation
- Proper change tracking (`changedFields` set)
- Confidence override mechanism is flexible and configurable

**Issues:**

**(a) Race condition in optimistic locking (HIGH)**

Lines 69-88 do:
1. Load record
2. Check version
3. Apply changes
4. Save

Between steps 1 and 4, another request could update the same record. The version check at step 2 would pass (it checks the in-memory loaded version), but the save at step 4 would overwrite the other request's changes.

**Fix**: The version check should be done as part of the save operation, using a conditional update:
```sql
UPDATE ... SET ... WHERE id = ? AND version = ?
```
If the update affects 0 rows, throw `VersionConflictException`. Alternatively, use Hibernate's `@Version` annotation if available through Joget's form framework.

**(b) Two separate saves when auto-transitioning (MEDIUM)**

The field update + status change is saved in one `dao.saveOrUpdate()` call (line 127), then the audit entry is saved in a separate call (line 137). If the second save fails, you have a status transition without an audit record. These should be in a single transaction, or the audit should be written by `StatusManager.transition()` as designed.

**(c) `loadRecord` uses different DAO method than `updateRecord`**

Line 41: `dao.load(null, tableName, id)` — uses `load()`
Line 69: `dao.load(null, tableName, id)` — same

But the `StatusManager.transition()` method (line 142 in StatusManager.java) uses:
```java
dao.load(tableName, tableName, recordId)
```

Note the different signatures — `StatusManager` passes `tableName` as the first argument (form definition), while `EnrichmentService` passes `null`. This inconsistency might cause different behavior depending on how Joget resolves the form definition internally.

### 3.3 Exception Classes

Clean and well-structured. `VersionConflictException` correctly carries `currentVersion` for the 409 response. No issues.

### 3.4 Activator.java

Standard OSGi activator. Clean. No issues.

---

## 4. Performance Considerations

### 4.1 N+1 Query Risk

The `GET /records` endpoint loads all records in a single `dao.find()` call — good. But `rowToMap()` iterates `getCustomProperties().keySet()` which may trigger lazy-loading per field depending on Joget's FormRow implementation. Profile this with 200-record pages.

### 4.2 No Connection Pooling Management

The service calls `AppUtil.getApplicationContext().getBean("formDataDao")` per request. This is fine for Joget's managed connections, but for the upcoming batch operations (confirm, split, merge) that need JDBC transactions (spec §3.4), you'll need `DataSource` access. Plan for this now.

### 4.3 Missing Caching

The `validationConfig` JSON is parsed on every `updateRecord()` call via `getConfidenceOverrides()`. Since this is a plugin property that rarely changes, parse it once at plugin initialization and cache the result.

---

## 5. Reliability Considerations

### 5.1 No Input Size Limits

The `updateRecord` endpoint accepts arbitrary JSON body with no size limit. A malicious request with thousands of fields could cause memory issues.

### 5.2 No Request Timeout

Long-running queries (e.g., GET /records with complex filters on large tables) have no timeout protection.

### 5.3 Error Messages Leak Internal Details

Line 246: `err.put("message", e.getMessage())` exposes Java exception messages to API callers. For 500 errors, this could leak table names, HQL queries, or stack trace fragments. Return a generic message for 500 errors and log the details server-side.

---

## 6. Summary of Findings

### Critical (Must fix before production)

| # | Issue | Location | Impact |
|---|---|---|---|
| C1 | HQL injection via unvalidated field names in filter/search/sort | EnrichmentApiPlugin.java lines 192, 202, 175 | Security vulnerability |
| C2 | Race condition in optimistic locking (TOCTOU) | EnrichmentService.java lines 69-127 | Data loss under concurrent edits |
| C3 | Status set directly instead of via StatusManager.transition() | EnrichmentService.java line 113 | Violates spec §3.5 Rule #1 |

### High (Should fix before production)

| # | Issue | Location | Impact |
|---|---|---|---|
| H1 | 8 of 12 spec endpoints not implemented | EnrichmentApiPlugin.java | 67% of functionality missing |
| H2 | Non-atomic audit logging (separate save from status change) | EnrichmentService.java lines 127, 137 | Audit gaps on partial failure |
| H3 | Error messages leak internal details on 500 responses | EnrichmentApiPlugin.java line 246 | Information disclosure |

### Medium (Address during development)

| # | Issue | Location | Impact |
|---|---|---|---|
| M1 | Default tableName mismatch (`trxEnrichment` vs `trx_enrichment`) | EnrichmentApiPlugin.json line 11 | Config confusion |
| M2 | Java 11 target instead of 17 | pom.xml lines 16-17 | Missed language features |
| M3 | `rowToMap()` emits `""` instead of `null` for absent fields | EnrichmentApiPlugin.java line 448 | JSON contract mismatch with spec |
| M4 | `validationConfig` parsed on every request, not cached | EnrichmentApiPlugin.java line 404 | Minor perf overhead |
| M5 | `EnrichmentService` instantiated per request unnecessarily | EnrichmentApiPlugin.java line 395 | Minor overhead |
| M6 | FormDataDao null form-definition parameter | EnrichmentApiPlugin.java lines 214-216 | Possibly incorrect Joget API usage |
| M7 | Missing `maxPageSize` and `enableBatchOperations` properties | EnrichmentApiPlugin.json | Spec deviation |
| M8 | Health endpoint missing `timestamp` and `uptime_ms` | EnrichmentApiPlugin.java line 132 | Minor spec deviation |

---

## 7. Recommended Implementation Order

Given the current state, I recommend implementing the remaining endpoints in this order:

1. **POST /records/{id}/status** — most-used operation, unlocks all UI status transitions
2. **POST /records/status (batch)** — unlocks batch actions in datalist
3. **DELETE /records/{id}** — simple, completes CRUD
4. **GET /summary** — simple aggregate, useful for dashboard
5. **GET /reconciliation/{statementId}** — needed for confirmation dialog
6. **POST /records/confirm** — the central business operation, needs reconciliation first
7. **POST /records/{id}/split** — complex, needs JDBC transactions
8. **POST /records/merge** — complex, needs JDBC transactions

But first: fix C1-C3 in the existing code.
