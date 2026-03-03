# Enrichment API Plugin — Technical Specification

**Plugin:** `enrichment-api` v1.0.0
**Platform:** Joget DX Enterprise Edition 8.1.6
**Application:** gamBackOffice
**API Builder Service:** enrichmentService
**Date:** 2026-03-02

---

## 1. Overview

The **enrichment-api** plugin is a custom Joget DX API Builder plugin that provides the REST backend for the Manual Enrichment Workspace (F01.05). It serves as the data access layer between the enrichment-workspace UI plugin and Joget form data.

The plugin is **form-agnostic**: the target table is configured via plugin properties, and all form fields are iterated dynamically at runtime using `FormRow.getCustomProperties().keySet()`. There is no hardcoded field map — adding or removing fields from the form requires zero code changes.

This design follows the native Joget patterns observed in `FormUtil.formRowSetToJson()`, `AppFormAPI`, and `FormListDataJsonController`.

### 1.1 Architecture Position

```
F01.03 Bank Consolidated ──┐
                           ├──> F01.05 trx_enrichment <──> enrichment-api <──> enrichment-workspace UI
F01.04 Secu Consolidated ──┘                                                        │
                                                                                     ▼
                                                                              F01.06 posting_operation
```

The automated enrichment pipeline populates F01.05 records from consolidated bank (F01.03) and securities (F01.04) transactions. The enrichment-api exposes these records to the workspace UI for review, editing, splitting, merging, and confirmation for GL posting.

### 1.2 Plugin Identity

| Property | Value |
|----------|-------|
| Plugin Name | `EnrichmentAPI` |
| Label | Enrichment API |
| Version | 1.0.0 |
| Class Name | `org.joget.gam.enrichment.api.EnrichmentApiPlugin` |
| Tag | `enrichment` |
| Tag Description | Enrichment workspace operations — records, actions, reconciliation |
| Icon | `<i class="fas fa-exchange-alt"></i>` |
| Bundle Activator | `org.joget.gam.enrichment.api.Activator` |
| OSGi Framework | Apache Felix maven-bundle-plugin 5.1.9 |

### 1.3 Design Principles

The v1.0.0 architecture was driven by three principles:

**No hardcoding.** The plugin does not contain a field map, field list, or any reference to specific form element IDs. It works with whatever fields exist on the form at runtime.

**Maintainability.** Adding fields to the Joget form automatically exposes them through the API. No recompilation, no redeployment.

**Simplicity.** Configuration is minimal: one table name and one default sort field, both set via plugin properties in the API Builder UI.

These principles are consistent with native Joget patterns — `FormUtil.formRowSetToJson()` iterates `row.getCustomProperties().keySet()`, `AppFormAPI` discovers form structure at runtime via `recursiveGenerateDefinition()`, and `FormRowDataListBinder` discovers columns from form definitions dynamically.

### 1.4 Development Phases

| Phase | Endpoints | Status | Description |
|-------|-----------|--------|-------------|
| 0 | `GET /health` | Complete | Health check, version, configured table |
| 1 | `GET /records` | Complete | Paginated listing with generic filters and sorting |
| 2 | `GET /records/{id}` | Planned | Single record detail with all fields |
| 3 | `PUT /records/{id}`, `POST /records/{id}/status` | Planned | Inline editing and status transitions |
| 4 | `POST /records/confirm` | Planned | Batch confirm for posting (F01.05 → F01.06) |
| 5 | `POST /records/{id}/split`, `POST /records/merge` | Planned | Split and merge operations |
| 6 | `GET /reconciliation/{statementId}` | Planned | Statement reconciliation summary |
| 7 | `POST /posting/{id}/revoke` | Planned | Revoke pending posting operation |

---

## 2. Technical Foundation

### 2.1 Joget API Builder Integration

**Endpoint Discovery:** The API Builder scans all methods annotated with `@Operation` on the plugin class. Each operation's path is prefixed with the tag (`/enrichment`), forming the full URL path (e.g., `/jw/api/enrichment/records`).

**Parameter Injection:** The API Builder's `runOperation()` method reads `@Param` annotations and injects query parameters by calling `castValue()` to convert strings to the declared Java type (`String`, `Integer`, etc.). Null parameters return null from `castValue`.

**Authentication:** API Builder handles authentication via `api_id` and `api_key` headers. The plugin does not implement its own auth.

### 2.2 Configuration via Plugin Properties

The plugin uses two configurable properties, set in the API Builder UI when adding the plugin to a service:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `tableName` | textfield | `trx_enrichment` | Joget form table name (without `app_fd_` prefix) |
| `defaultSort` | textfield | `dateCreated` | Default sort field when no `sort` parameter is provided |

This means the same plugin JAR can serve different forms — point `tableName` at `trx_enrichment` for F01.05, or at a different table for F01.06 or any other form.

### 2.3 FormDataDao Usage

All database access uses Joget's `FormDataDao` Spring bean. This is critical for compatibility with Joget's dynamic Hibernate entity mapping.

#### 2.3.1 Key Conventions

**Table Name:** Pass just the form table name (e.g., `trx_enrichment`). FormDataDao adds the `app_fd_` prefix automatically.

**HQL Property Names:** Hibernate property names match the **form element IDs** (e.g., `transaction_date`), **NOT** the database column names (e.g., `c_transaction_date`). The `c_` prefix is added by FormDataDao's dynamic mapping layer for DB columns only.

**Conditions:** Use `e.customProperties.<field_id>` syntax in HQL WHERE clauses.

```java
// CORRECT
"WHERE e.customProperties.processing_status = ?"

// WRONG — will throw "could not resolve property"
"WHERE e.customProperties.c_processing_status = ?"
```

**Sort Fields:** Pass just the form element ID (e.g., `transaction_date`). FormDataDao's `internalFind()` auto-prepends `customProperties.` for custom fields. Standard fields (`dateCreated`, `dateModified`) are used as-is.

**Empty Conditions:** When no filters apply, pass an empty string `""` (not `null`) for the condition parameter. FormDataDao's `internalCount()` directly concatenates the condition string; passing `null` produces the literal string `"null"` in the HQL query.

```java
// CORRECT
String condition = cond.length() > 0 ? cond.toString() : "";
Object[] paramsArr = params.isEmpty() ? new Object[0] : params.toArray();

// WRONG — produces "SELECT COUNT(*) FROM app_fd_trx_enrichment e null"
String condition = cond.length() > 0 ? cond.toString() : null;
```

#### 2.3.2 Dynamic Field Iteration

The plugin follows the same pattern as `FormUtil.formRowSetToJson()` in Joget core:

```java
// Native Joget pattern — iterate all keys dynamically
for (Object key : row.getCustomProperties().keySet()) {
    String fieldId = key.toString();
    Object val = row.getProperty(fieldId);
    m.put(fieldId, val != null ? val : "");
}
```

JSON keys are the form element IDs exactly as they appear in the form definition — no camelCase conversion, no prefix manipulation. This means:
- The API consumer (enrichment-workspace UI) uses the same field names as the Joget form
- Adding a field to the form immediately exposes it in the API response
- The spec does not need to enumerate individual fields

#### 2.3.3 Standard vs. Custom Properties

| Type | Examples | Sort Prefix | HQL Path |
|------|----------|-------------|----------|
| Standard | `id`, `dateCreated`, `dateModified`, `createdBy`, `modifiedBy` | None (use as-is) | `e.<property>` |
| Custom | All form element fields | `customProperties.` (auto-added) | `e.customProperties.<field_id>` |

Standard metadata fields are handled explicitly in `rowToMap()` and included in every response: `id`, `dateCreated` (as epoch ms), `dateModified` (as epoch ms), `createdBy`, `modifiedBy`.

#### 2.3.4 Why This Works

FormDataDao's `createSessionFactory()` method dynamically generates Hibernate entity mappings from form definitions. In `FormDataDaoImpl.java` line 1092–1093:

```java
String propName = field;                          // e.g. "transaction_date"
String columnName = FORM_PREFIX_COLUMN + field;   // e.g. "c_transaction_date"
```

The Hibernate property name is the **form element ID** (no prefix), while the DB column name gets the `c_` prefix. `FormRow.getCustomProperties()` returns `this` (the FormRow itself IS the Properties map), so keys match the Hibernate property names.

### 2.4 OSGi Bundle Configuration

Key `pom.xml` settings:

```xml
<Import-Package>
    org.joget.api.annotations,
    org.joget.api.model,
    org.joget.api.service,
    org.joget.apps.form.dao,
    org.joget.apps.form.model,
    org.joget.apps.form.service,
    org.joget.apps.app.service,
    org.joget.commons.util,
    org.joget.plugin.base,
    org.joget.plugin.property.model,
    org.osgi.framework;version="1.3.0",
    *;resolution:=optional
</Import-Package>
<DynamicImport-Package>*</DynamicImport-Package>
```

Java target: 11 (compiler), running on Java 17 (Tomcat 9.0.90).

### 2.5 API Builder Configuration

| Property | Value |
|----------|-------|
| Service Name | `enrichmentService` |
| API Builder ID | `API-c46c424a-51c5-491d-b348-a785b4b59c24` |
| Current ENABLED_PATHS | `get:/health;get:/records` |
| Authentication | API ID + API Key headers |

---

## 3. API Endpoints

All endpoints are available under the base URL `/jw/api/enrichment`. Authentication is via `api_id` and `api_key` HTTP headers.

### 3.1 Endpoint Summary

| Method | Path | Phase | Summary |
|--------|------|-------|---------|
| GET | `/health` | 0 | Health check |
| GET | `/records` | 1 | List records (paginated, filtered, sorted) |
| GET | `/records/{id}` | 2 | Get single record detail |
| PUT | `/records/{id}` | 3 | Inline save (update fields) |
| POST | `/records/{id}/status` | 3 | Change processing status |
| POST | `/records/confirm` | 4 | Confirm for posting (batch: create F01.06 records) |
| POST | `/records/{id}/split` | 5 | Split transaction into N child allocations |
| POST | `/records/merge` | 5 | Merge multiple transactions into one |
| GET | `/reconciliation/{statementId}` | 6 | Reconciliation summary for a statement |
| POST | `/posting/{id}/revoke` | 7 | Revoke a pending F01.06 posting operation |

### 3.2 GET /health

Returns a health check confirming the plugin is loaded and responding. Includes the configured table name for verification.

**Response (200):**

```json
{
  "status": "ok",
  "plugin": "EnrichmentAPI",
  "version": "1.0.0",
  "tableName": "trx_enrichment"
}
```

### 3.3 GET /records

Returns paginated records from the configured form table. Supports generic filtering by any form field and configurable sorting.

#### 3.3.1 Query Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `filter` | String | No | — | Filter as field=value pairs, comma-separated. Example: `processing_status=new,source_type=bank_total_trx` |
| `search` | String | No | — | Substring search on a field, format: `field:value`. Example: `customer_id:CUST001` |
| `page` | Integer | No | 1 | Page number (1-based) |
| `pageSize` | Integer | No | 20 | Records per page (max 200) |
| `sort` | String | No | configured default | Sort field — any form element ID, or `created`/`modified` for metadata |
| `order` | String | No | `asc` | Sort direction: `asc` or `desc` |

**Generic filter design:** The `filter` parameter accepts any form element ID as a key. This means the API automatically supports filtering by any field on the form without code changes. Exact match (`=`) is used for filter pairs; substring match (`LIKE %value%`) is used for the search parameter.

**Sort field:** Any form element ID can be passed as the sort field. The shortcuts `created` and `modified` are mapped to `dateCreated` and `dateModified` respectively. Unknown field names are passed through to FormDataDao as-is — if the field doesn't exist on the form, Hibernate will throw an error.

#### 3.3.2 Response Structure (200)

```json
{
  "records": [
    {
      "id": "abc123",
      "dateCreated": 1737936000000,
      "dateModified": 1737936000000,
      "createdBy": "admin",
      "modifiedBy": "admin",
      "source_type": "bank_total_trx",
      "source_transaction_id": "TRX-001",
      "transaction_date": "2026-01-15",
      "amount": "50000.00",
      "currency": "EUR",
      "processing_status": "enriched",
      "customer_id": "CUST001",
      "...": "all other form fields returned dynamically"
    }
  ],
  "page": 1,
  "pageSize": 20,
  "total": 145,
  "totalPages": 8,
  "sort": "dateCreated",
  "order": "asc",
  "ms": 42
}
```

Note: JSON keys are the form element IDs exactly as defined in the Joget form (snake_case). All custom fields present on the FormRow are included automatically. Empty values are returned as `""`.

#### 3.3.3 Error Response (500)

```json
{
  "error": "INTERNAL_ERROR",
  "message": "...",
  "ms": 5
}
```

#### 3.3.4 HQL Query Generation

The endpoint dynamically builds HQL conditions from the `filter` and `search` parameters. When no filters are present, an empty condition string is passed to FormDataDao (not null).

Example with filters (`?filter=processing_status=enriched,source_type=bank_total_trx&search=customer_id:CUST`):
```
WHERE e.customProperties.processing_status = ? AND e.customProperties.source_type = ? AND e.customProperties.customer_id LIKE ?
```

Example without filters: `""` (empty string)

---

## 4. Planned Endpoints (Phases 2–7)

### 4.1 GET /records/{id} (Phase 2)

Returns the full detail of a single enrichment record. Uses `FormDataDao.load(null, tableName, id)` to fetch by primary key. Response uses the same dynamic `rowToMap()` iteration — all form fields included automatically.

### 4.2 PUT /records/{id} (Phase 3)

Inline save for editing a single record. Accepts a JSON body with the fields to update. Only fields present in the request body are modified; absent fields are left unchanged. Uses `FormDataDao.saveOrUpdate()` to persist.

### 4.3 POST /records/{id}/status (Phase 3)

Changes the processing status of a record. Enforces the status lifecycle transitions. Invalid transitions return 400 Bad Request.

**Valid transitions:** `enriched`/`adjusted`/`paired`/`in_review` → `ready`; `error`/`manual_review` → `new` (reprocess); `ready` → `in_review`.

### 4.4 POST /records/confirm (Phase 4)

Batch confirmation: transfers selected F01.05 records to F01.06 for GL posting. Implementation will use a second `tableName` property (configurable) for the target posting table. Validation and field copying will be driven by the form structure, not hardcoded field lists.

### 4.5 POST /records/{id}/split (Phase 5)

Splits a single F01.05 record into N child records with proportional amounts. The parent record is set to status `superseded`, and child records receive lineage metadata. Amount fields for the split are specified in the request body by their form element IDs.

### 4.6 POST /records/merge (Phase 5)

Merges multiple F01.05 records into a single combined record. Source records are set to `superseded` and linked via group metadata. The merged record receives summed amounts.

### 4.7 GET /reconciliation/{statementId} (Phase 6)

Returns the reconciliation summary for a statement: source input totals, output totals (pending/posted + current batch), remaining active totals, and per-currency discrepancies.

### 4.8 POST /posting/{id}/revoke (Phase 7)

Revokes a pending F01.06 posting operation, returning the associated F01.05 records to editable status. Only available when the posting record status is `pending` (not yet posted by GL engine).

---

## 5. Project Structure

```
enrichment-api/
  pom.xml
  src/main/java/org/joget/gam/enrichment/api/
    Activator.java              # OSGi bundle activator
    EnrichmentApiPlugin.java    # Main plugin (all endpoints)
  src/main/resources/properties/
    EnrichmentApiPlugin.json    # Plugin property options (tableName, defaultSort)
```

### 5.1 Dependencies

| Dependency | Version | Scope | Purpose |
|------------|---------|-------|---------|
| `wflow-core` | 8.1-SNAPSHOT | provided | Joget core API (FormDataDao, AppUtil, etc.) |
| `apibuilder_api` | 8.1-SNAPSHOT | provided | API Builder annotations and base classes |
| `javax.servlet-api` | 3.1.0 | provided | Servlet API |
| `org.json` | 20230227 | provided | JSON utilities |

### 5.2 Build & Deploy

**Build:** `mvn clean package` produces `enrichment-api-8.1.0-SNAPSHOT.jar` in the `target/` directory.

**Deploy:** Upload via Joget Plugin Manager (Settings → Manage Plugins → Upload Plugin) or copy to `wflow/app_plugins/`.

**Configure:** After deploying, open the API Builder Design view for `enrichmentService`, select the Enrichment API plugin, and set the `tableName` property (default: `trx_enrichment`).

**Enable Endpoints:** After deploying a new version with new endpoints, enable the new paths in `ENABLED_PATHS` via the API Builder Design view.

---

## 6. Lessons Learned & Pitfalls

### 6.1 getTag() Must Not Contain Placeholders

Returning `"enrichment/{prefix}"` from `getTag()` caused `ApiBuilder.java` (lines 379–388) to try resolving the `{prefix}` variable from saved properties. When resolution failed, the entire plugin was silently skipped, producing the "No operations defined in spec!" error in the Swagger preview. Fix: `getTag()` must return a plain string.

### 6.2 FormDataDao Null Condition Bug

Passing `null` as the condition parameter to `FormDataDao.count()` produces the HQL query `SELECT COUNT(*) FROM app_fd_trx_enrichment e null` because `internalCount()` directly concatenates the condition string without a null check. Always pass `""` instead of `null`. Note: `internalFind` HAS a null check but `internalCount` does not — this is an inconsistency in Joget core.

### 6.3 Hibernate Property Names vs. DB Column Names

Using `c_transaction_date` in HQL conditions and sort fields causes `"could not resolve property"` errors. FormDataDao's dynamic Hibernate mapping uses form element IDs as property names (e.g., `transaction_date`) and only applies the `c_` prefix for the actual database column name. The `c_` prefix is never part of the Hibernate entity model.

### 6.4 Why Not Hardcode a Field Map

The v0.x versions of this plugin used a 34-entry `FIELD_MAP` that mapped form element IDs to camelCase JSON keys. This was wrong for three reasons: (1) it coupled the compiled plugin to one specific form, (2) adding a field required recompilation, (3) it deviated from native Joget patterns where `FormUtil.formRowSetToJson()`, `AppFormAPI`, and `FormListDataJsonController` all iterate FormRow keys dynamically. The v1.0.0 refactoring eliminated the field map entirely.

### 6.5 API Builder ENABLED_PATHS

New operations must be explicitly enabled in the API Builder Design view. The `ENABLED_PATHS` property in the saved API Builder config JSON controls which operations are routed.

---

## 7. Companion Plugin: enrichment-workspace

| Property | Value |
|----------|-------|
| Plugin Name | Enrichment Workspace |
| Plugin Type | UserviewMenu (`userview_enrichmentWorkspace`) |
| Version | 0.2.0 |
| API Consumption | Calls `GET /jw/api/enrichment/records` with `api_id`/`api_key` headers |
| Plugin Properties | `apiId`, `apiKey` (configured in Userview Builder) |

The workspace UI needs to use form element IDs (snake_case) as JSON keys when consuming the v1.0.0 API, since the camelCase mapping was removed.

---

## 8. Appendix

### 8.1 Example curl Requests

**Health Check:**
```bash
curl -s http://localhost:8082/jw/api/enrichment/health \
  -H 'api_id: API-c46c424a-51c5-491d-b348-a785b4b59c24' \
  -H 'api_key: 8c04d5332aa34484a62fe1fb1e6e5900'
```

**List Records (page 1, 5 per page):**
```bash
curl -s 'http://localhost:8082/jw/api/enrichment/records?page=1&pageSize=5' \
  -H 'accept: application/json' \
  -H 'api_id: API-c46c424a-51c5-491d-b348-a785b4b59c24' \
  -H 'api_key: 8c04d5332aa34484a62fe1fb1e6e5900'
```

**Filter by Status and Source Type:**
```bash
curl -s 'http://localhost:8082/jw/api/enrichment/records?filter=processing_status=enriched,source_type=bank_total_trx' \
  -H 'accept: application/json' \
  -H 'api_id: API-c46c424a-51c5-491d-b348-a785b4b59c24' \
  -H 'api_key: 8c04d5332aa34484a62fe1fb1e6e5900'
```

**Search by Customer ID:**
```bash
curl -s 'http://localhost:8082/jw/api/enrichment/records?search=customer_id:CUST001' \
  -H 'accept: application/json' \
  -H 'api_id: API-c46c424a-51c5-491d-b348-a785b4b59c24' \
  -H 'api_key: 8c04d5332aa34484a62fe1fb1e6e5900'
```

**Sort by Amount Descending:**
```bash
curl -s 'http://localhost:8082/jw/api/enrichment/records?sort=amount&order=desc' \
  -H 'accept: application/json' \
  -H 'api_id: API-c46c424a-51c5-491d-b348-a785b4b59c24' \
  -H 'api_key: 8c04d5332aa34484a62fe1fb1e6e5900'
```

### 8.2 Related Documents

| Document | Location |
|----------|----------|
| F01.05 + F01.06 Architecture | `_dev-v3/_enrichment/f01-05-f01-06-architecture.md` |
| F01.05 UX Data Layer | `_dev-v3/_enrichment/f01-05-ux-data-layer.md` |
| F01.05 UX Interaction Layer | `_dev-v3/_enrichment/f01-05-ux-interaction-layer.md` |
| Form Definition | `trxEnrichmentForm.json` (124KB) |
| API Builder Config | `API-c46c424a-51c5-491d-b348-a785b4b59c24.json` |

---

*End of Specification*
