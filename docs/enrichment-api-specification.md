# Enrichment API v1.0.0 Specification

**Plugin**: enrichment-api v1.0.0
**Platform**: Joget DX 8.1.6 API Builder Plugin (ApiPluginAbstract)
**Scope**: F01.05 Manual Enrichment only (reads F01.03/F01.04 for reconciliation)
**Design Principle**: Configuration-driven, no hardcoding, dynamic field iteration, gam-framework for state management

---

## 1. Overview

### 1.1 Architecture Position

The enrichment-api plugin has a single responsibility: **manual enrichment of F01.05 transactions**, preparing them for posting.

- **F01.05** (Enrichment transactions): **Read/Write** — The plugin's only table. Status transitions, field enrichment, split/merge operations, validation, and confirmation.
- **F01.03 & F01.04** (Bank & Securities totals): **Read-only** — Referenced during reconciliation to verify that enriched amounts tie back to source input totals. The plugin never creates, updates, or deletes F01.03/F01.04 records. These tables are populated upstream by the statement import pipeline.

**What this plugin does NOT do**: It does not create F01.06 posting records, does not interact with the GL posting engine, and does not manage posting operations. Once a record reaches `CONFIRMED` status, it is picked up by a separate downstream process (posting plugin) that creates F01.06 records and handles the posting lifecycle.

**Data flow**: Statement import → F01.03/F01.04 → *(upstream)* → F01.05 → **this plugin (enrichment)** → F01.05 at `CONFIRMED` → *(downstream posting plugin)* → F01.06 → acc_post

### 1.2 Plugin Identity

| Property | Value |
|---|---|
| **Plugin Name** | enrichment-api |
| **Version** | 1.0.0 |
| **Plugin Type** | API Builder (ApiPluginAbstract) |
| **Service Name** | enrichmentService |
| **Joget Version** | 8.1.6+ |
| **Tag** | enrichment |
| **Deployment** | JAR via Joget plugin console |

### 1.3 Design Principles

1. **No Hardcoding**: All field names, validation rules, reconciliation parameters, and table names are configured via JSON plugin properties. The plugin reads these at runtime and applies them dynamically.

2. **Form-Agnostic Field Iteration**: The plugin does not maintain a static field map. Instead, it iterates `FormRow.getCustomProperties().keySet()` at runtime to build response objects and copy data between forms. This allows form structure changes without recompilation.

3. **Configuration-Driven**: Validation rules, reconciliation tolerance, field mappings, and business logic parameters live in a single `validationConfig` JSON property. This enables non-technical users to adjust business rules via the Joget UI.

4. **gam-framework for State Management**: All status transitions are delegated to `StatusManager.transition()` from the shared gam-framework library. The plugin never sets status strings directly. This ensures a single source of truth for the state machine, automatic audit logging, and consistency across all GAM plugins. See §3.5 for integration details.

5. **Standard Joget Patterns**:
   - Uses `FormDataDao` for all reads and standard updates
   - Uses `FormUtil.formRowSetToJson()` for row serialization
   - Follows `FormListDataJsonController` patterns for GET endpoints
   - Uses direct JDBC for batch operations requiring all-or-nothing transaction semantics (split, merge, confirm)

---

## 2. Plugin Configuration

### 2.1 Plugin Properties

Configuration is managed through the API Builder UI. All properties are required unless noted otherwise.

#### Core Properties

| Property | Type | Default | Description |
|---|---|---|---|
| **tableName** | String | trx_enrichment | Primary table for F01.05 enrichment transactions |
| **defaultSort** | String | dateCreated | Default sort field when none specified in request |
| **validationConfig** | JSON Text | (see §2.2) | Validation rules, reconciliation config, field mappings |
| **maxPageSize** | Integer | 200 | Maximum allowed page size for GET /records |
| **enableBatchOperations** | Boolean | true | Enable split, merge, confirm endpoints |

#### API Builder Configuration

```
Service Name: enrichmentService
API ID: enrichment

ENABLED_PATHS:
  - GET /health
  - GET /records
  - GET /records/{id}
  - GET /summary
  - GET /reconciliation/{statementId}
  - PUT /records/{id}
  - POST /records/{id}/status
  - POST /records/status
  - DELETE /records/{id}
  - POST /records/confirm
  - POST /records/{id}/split
  - POST /records/merge
```

### 2.2 Validation Configuration (validationConfig Property)

This JSON structure defines all business rules, validation logic, field mappings, and reconciliation parameters. It is read at plugin initialization and used throughout request processing.

```json
{
  "baseCurrency": "EUR",

  "requiredFields": [
    "internal_type",
    "original_amount",
    "validated_currency",
    "customer_code",
    "debit_credit"
  ],

  "conditionalRequirements": [
    {
      "condition": {
        "field": "internal_type",
        "matchPattern": "SEC_.*|BOND_.*"
      },
      "requiredFields": ["resolved_asset_id"]
    },
    {
      "condition": {
        "field": "internal_type",
        "excludePattern": "CASH_.*|FEE_.*"
      },
      "requiredFields": ["counterparty_short_code"]
    },
    {
      "condition": {
        "field": "validated_currency",
        "notEquals": "EUR"
      },
      "requiredFields": ["fx_rate_to_eur"]
    },
    {
      "condition": {
        "field": "resolved_asset_id",
        "isNotEmpty": true
      },
      "requiredFields": ["asset_category"]
    }
  ],

  "reconciliation": {
    "amountField": "total_amount",
    "currencyField": "validated_currency",
    "statementField": "statement_id",
    "originField": "origin",
    "manualOriginValue": "manual",
    "statusField": "status",
    "sourceTables": [
      {
        "tableName": "trx_bank_total",
        "amountField": "total_amount",
        "currencyField": "currency",
        "statementField": "statement_id"
      },
      {
        "tableName": "trx_secu_total",
        "amountField": "total_amount",
        "currencyField": "currency",
        "statementField": "statement_id"
      }
    ],
    "tolerance": {
      "EUR": 0.02,
      "USD": 0.02,
      "_default": 0.05
    }
  },

  "splitMerge": {
    "amountField": "original_amount",
    "feeField": "fee_amount",
    "totalField": "total_amount",
    "eurAmountField": "base_amount_eur",
    "fxRateField": "fx_rate_to_eur",
    "customerField": "customer_code",
    "originField": "origin",
    "parentIdField": "parent_enrichment_id",
    "groupIdField": "group_id",
    "sequenceField": "split_sequence",
    "lineageNoteField": "lineage_note",
    "statusField": "status"
  },

  "confirmation": {
    "confirmedByField": "confirmed_by",
    "confirmedAtField": "confirmed_at"
  }
}
```

#### Configuration Field Definitions

**baseCurrency**: The home/reporting currency. Used in reconciliation tolerance lookups and FX calculations.

**requiredFields**: List of F01.05 form element IDs that must be non-null and non-empty for confirmation or submission. Field IDs are used as-is (no c_ prefix, no camelCase conversion).

**conditionalRequirements**: Array of condition+requiredFields pairs. Each condition evaluates one field using pattern matching or value comparison. If the condition is true, all fields in requiredFields become required.

- `matchPattern`: Regex pattern (Java Pattern syntax). Field value must match.
- `excludePattern`: Regex pattern. Field value must NOT match.
- `notEquals`: Exact string comparison. Field value must differ.
- `isNotEmpty`: Boolean. True if field must have a value.
- `equals`: Exact string comparison. Field value must match.

**reconciliation**: Defines how to compute per-statement, per-currency balances.

- `amountField`: Form element ID in F01.05 containing the amount to sum.
- `currencyField`: Form element ID in F01.05 containing the currency.
- `statementField`: Form element ID in F01.05 linking to a statement ID.
- `originField`: Form element ID in F01.05 indicating whether record originated from manual entry or system-generated.
- `manualOriginValue`: String value of originField for manually-created records.
- `statusField`: Form element ID in F01.05 containing status.
- `sourceTables`: Array of source table definitions (F01.03, F01.04). Each has tableName, amountField, currencyField, and statementField.
- `tolerance`: Per-currency tolerance for reconciliation discrepancy. Use `_default` for unmapped currencies. Values are decimal (e.g., 0.02 = ±0.02).

**splitMerge**: Field ID mappings for split and merge operations.

**confirmation**: Field ID mappings for audit fields set on the F01.05 record when confirmed (who confirmed it and when).

---

## 3. Technical Foundation

### 3.1 FormDataDao Conventions

The plugin adheres to standard Joget FormDataDao patterns:

1. **No c_ Prefix in HQL**: Hibernate property names match form element IDs without the c_ prefix. A form element with ID `internal_type` is queried as `where internal_type = ?`, not `where c_internal_type = ?`.

2. **Empty String, Not Null**: FormDataDao treats empty strings (`""`) and null differently. Conditions must use empty string to check for absent data:
   ```java
   formDataDao.loadFormDataList(tableName,
     null,                           // columns (null = all)
     "resolved_asset_id = ''",       // condition (empty string check)
     null,                           // order by
     null, null, null                // page params
   );
   ```

3. **Sort Field Conventions**: The sort field must match a form element ID (or a standard metadata field like `dateCreated`, `dateModified`). Complex sorts (e.g., composite keys) are handled by parsing the sort parameter and building HQL ORDER BY clauses.

4. **Null and Empty Handling**: When loading records, the plugin treats both null and empty string as "absent". JSON serialization converts both to null, then to empty in JSON responses. Filters must account for this.

### 3.2 Dynamic Field Iteration Pattern

Rather than maintaining a static field map, the plugin dynamically iterates form fields at runtime:

```java
// Load a record
FormRow row = formDataDao.loadFormRow(tableName, primaryKeyValue);

// Iterate all fields (custom + standard properties)
Map<String, Object> rowMap = new HashMap<>();
if (row != null) {
  for (String fieldId : row.getCustomProperties().keySet()) {
    Object value = row.getProperty(fieldId);
    rowMap.put(fieldId, value != null ? value : null);
  }
  // Add standard metadata
  rowMap.put("id", row.getId());
  rowMap.put("dateCreated", row.getProperty("dateCreated"));
  rowMap.put("dateModified", row.getProperty("dateModified"));
}
return rowMap;
```

This pattern ensures:
- New form fields are automatically included in API responses without recompilation.
- Field name changes are reflected dynamically.
- No breaking changes when form structure evolves.
- Field IDs remain snake_case (form element IDs), consistent with companion plugins.

### 3.3 Standard vs. Custom Properties

**Standard Properties** (maintained by Joget):
- `id`: Primary key
- `dateCreated`: Record creation timestamp
- `dateModified`: Last modification timestamp
- `createdBy`: User who created the record
- `modifiedBy`: User who last modified the record
- `version`: Optimistic locking version counter

**Custom Properties**: All form element IDs, populated by users or business logic.

### 3.4 Transaction Control Strategy

The plugin uses a hybrid transaction strategy:

1. **Reads and Simple Updates**: Use `FormDataDao` with its built-in transaction management.
   ```java
   FormRow row = formDataDao.loadFormRow(tableName, id);
   formDataDao.insertFormData(formDataDao.addFormDataFromMap(tableName, updates));
   ```

2. **Batch Operations (all-or-nothing)**: Use direct JDBC with explicit transaction control.
   ```java
   Connection conn = workflowManager.getConnection();
   try {
     conn.setAutoCommit(false);
     // Execute multiple DML statements
     for (...) {
       stmt.executeUpdate(insertSQL);
       stmt.executeUpdate(updateSQL);
     }
     conn.commit();
   } catch (Exception e) {
     conn.rollback();
     throw e;
   }
   ```

This is required for:
- `POST /records/confirm`: Validate and transition multiple F01.05 records to CONFIRMED atomically.
- `POST /records/{id}/split`: Create N child records and mark parent as superseded.
- `POST /records/merge`: Create merged record and mark sources as superseded.

### 3.5 gam-framework Integration

The enrichment-api plugin depends on the **gam-framework** shared library for all status lifecycle management. The gam-framework provides:

- **`Status` enum** — 28 status values across all GAM entities (no string literals). Enrichment-relevant statuses: `NEW`, `PROCESSING`, `ENRICHED`, `IN_REVIEW`, `ADJUSTED`, `READY`, `CONFIRMED`, `SUPERSEDED`, `PAIRED`, `MANUAL_REVIEW`, `ERROR`.
- **`EntityType` enum** — Maps entity names to Joget table names. This plugin uses `EntityType.ENRICHMENT` → `trx_enrichment`.
- **`StatusManager`** — Central transition engine. Contains the authoritative `TRANSITIONS` map defining all valid state changes. Validates, executes, and audit-logs every transition.
- **`TransitionAuditEntry`** — Immutable audit record written to the `audit_log` table on every transition (entity_type, entity_id, from_status, to_status, triggered_by, reason, timestamp).
- **`InvalidTransitionException`** — Checked exception thrown when a transition is not allowed.

#### Dependency (pom.xml)

```xml
<dependency>
    <groupId>com.fiscaladmin.gam</groupId>
    <artifactId>gam-framework</artifactId>
    <version>8.1-SNAPSHOT</version>
</dependency>
```

The gam-framework JAR is deployed to `{JOGET_HOME}/wflow/lib/` (shared classpath), not bundled inside the plugin OSGi JAR.

#### Usage Pattern

All status changes in the enrichment-api plugin MUST go through StatusManager:

```java
import com.fiscaladmin.gam.framework.status.*;

StatusManager manager = new StatusManager();
FormDataDao dao = StatusManager.getFormDataDao();

// Execute a transition (validates + writes + audits)
try {
    manager.transition(
        dao,
        EntityType.ENRICHMENT,
        recordId,                    // e.g., "ENR-042"
        Status.READY,                // target status
        "enrichment-api",            // triggeredBy (plugin name for audit)
        "Analyst marked as ready"    // reason (for audit trail)
    );
} catch (InvalidTransitionException e) {
    // Return 400 with valid transitions
    Set<Status> valid = manager.getValidTransitions(
        EntityType.ENRICHMENT, currentStatus);
    // Build error response listing valid targets
}
```

#### Rules

1. **Never set status directly**: Do not call `row.setProperty("status", "ready")`. Always use `StatusManager.transition()`.
2. **Use enum constants**: `Status.ENRICHED.getCode()`, never `"enriched"` as a string literal.
3. **Let the framework audit**: Do not create separate audit entries — `StatusManager.transition()` writes to `audit_log` automatically.
4. **Pre-validate for UI**: Use `manager.getValidTransitions(entityType, currentStatus)` to populate status dropdown options in the workspace UI.
5. **Batch operations**: For batch status changes (confirm, split, merge), each record's transition must still go through `StatusManager.transition()` individually within the JDBC transaction. If any transition fails, the entire batch rolls back.

---

## 4. F01.05 Status Lifecycle

**Source of truth**: The authoritative state machine is defined in `gam-framework` → `StatusManager.TRANSITIONS` for `EntityType.ENRICHMENT`. The enrichment-api plugin does not define its own transition rules — it delegates all transitions to `StatusManager.transition()`. The diagram below is a reference copy; if it diverges from StatusManager, the StatusManager wins.

### 4.1 ENRICHMENT State Machine (from gam-framework)

```
NEW → PROCESSING
PROCESSING → ENRICHED | ERROR | MANUAL_REVIEW
ENRICHED → IN_REVIEW | ADJUSTED | READY | PAIRED | MANUAL_REVIEW | SUPERSEDED
IN_REVIEW → ADJUSTED | READY | ENRICHED
ADJUSTED → READY | IN_REVIEW | ENRICHED
READY → CONFIRMED | ENRICHED | IN_REVIEW
PAIRED → READY | MANUAL_REVIEW
CONFIRMED (terminal within this plugin — downstream posting plugin may revoke back to ADJUSTED)
SUPERSEDED (terminal)
ERROR → NEW | MANUAL_REVIEW
MANUAL_REVIEW → NEW | ENRICHED | READY
```

### 4.2 Status Definitions

| Status | Enum | Meaning | UI Actions |
|---|---|---|---|
| **new** | `Status.NEW` | Created but not processed | Reprocess, Delete |
| **processing** | `Status.PROCESSING` | Batch enrichment in progress | (System only) |
| **enriched** | `Status.ENRICHED` | System enrichment complete | Mark as Ready, Split, Merge, Begin Review |
| **in_review** | `Status.IN_REVIEW` | Customer reviewing | Adjust, Pair, Mark as Ready |
| **adjusted** | `Status.ADJUSTED` | Customer modified fields | Mark as Ready, Split |
| **paired** | `Status.PAIRED` | Paired with another record | Mark as Ready |
| **ready** | `Status.READY` | Approved for confirmation | Confirm, Return to Workspace |
| **confirmed** | `Status.CONFIRMED` | Ready for downstream posting (terminal for this plugin) | None — downstream posting plugin takes over |
| **error** | `Status.ERROR` | Enrichment failed | Reprocess |
| **manual_review** | `Status.MANUAL_REVIEW` | Requires analyst | Manual resolution |
| **superseded** | `Status.SUPERSEDED` | Replaced by split/merge (terminal) | None |

### 4.3 Key Rules

1. **Terminal states within this plugin**: `CONFIRMED` and `SUPERSEDED` have no outgoing transitions in the ENRICHMENT state machine. Once a record reaches `CONFIRMED`, it leaves this plugin's scope. If a downstream posting is revoked, the posting plugin is responsible for transitioning the F01.05 record back to `ADJUSTED` via gam-framework's StatusManager.

2. **Partial updates preserve status**: PUT /records/{id} updates field values but never changes status. Status changes require an explicit POST /records/{id}/status call (which delegates to StatusManager).

3. **Confirmation gate**: POST /records/confirm filters for `status = ready`. All other statuses are silently excluded.

4. **Audit trail**: Every transition is automatically logged to the `audit_log` table by StatusManager, including entity_type, record_id, from_status, to_status, triggered_by ("enrichment-api"), reason, and timestamp.

---

## 5. API Endpoints

### 5.1 GET /health

**Purpose**: Health check; returns plugin status, version, and table configuration.

**Request**:
```
GET /health
```

**Response (200 OK)**:
```json
{
  "status": "ok",
  "plugin": "enrichment-api",
  "version": "1.0.0",
  "tableName": "trx_enrichment",
  "timestamp": "2024-07-15T10:30:45Z",
  "uptime_ms": 123456
}
```

**Error Responses**:
- **500 Internal Server Error**: Plugin not initialized or validation config missing.
  ```json
  {
    "status": "error",
    "message": "validationConfig property is not set or invalid JSON"
  }
  ```

---

### 5.2 GET /records

**Purpose**: Paginated listing of records from the configured table. Supports all 5 datalist views through generic filtering.

**Request**:
```
GET /records?filter=status=ready,source_type=bank&search=customer_code:CUST001&page=1&pageSize=50&sort=transaction_date&order=desc
```

**Query Parameters**:

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| filter | String | No | — | Comma-separated `field=value` pairs for exact match. Example: `status=ready,source_type=bank` |
| search | String | No | — | Substring search as `field:value`. Example: `customer_code:CUST001` (generates `LIKE %CUST001%`) |
| page | Integer | No | 1 | 1-based page number |
| pageSize | Integer | No | 20 | Records per page (max: configured maxPageSize) |
| sort | String | No | configured defaultSort | Any form element ID, or `created`/`modified` shortcuts |
| order | String | No | asc | `asc` or `desc` |

**HQL generation**: The plugin builds HQL conditions dynamically from the filter and search parameters. All field references use form element IDs (no `c_` prefix). When no filters are present, an empty string `""` is passed to FormDataDao (never null).

Example generated HQL:
```
WHERE e.customProperties.status = ? AND e.customProperties.source_type = ? AND e.customProperties.customer_code LIKE ?
```

**Response (200 OK)**:
```json
{
  "records": [
    {
      "id": "ENR-042",
      "dateCreated": 1737936000000,
      "dateModified": 1737936000000,
      "createdBy": "admin",
      "modifiedBy": "admin",
      "source_type": "bank",
      "origin": "pipeline",
      "transaction_date": "2026-01-15",
      "settlement_date": "2026-01-17",
      "internal_type": "SEC_BUY",
      "original_amount": "50000.00",
      "fee_amount": "125.00",
      "total_amount": "50125.00",
      "validated_currency": "EUR",
      "customer_code": "CUST001",
      "status": "ready",
      "...": "(all other form fields returned dynamically)"
    }
  ],
  "page": 1,
  "pageSize": 50,
  "total": 145,
  "totalPages": 3,
  "sort": "transaction_date",
  "order": "desc",
  "ms": 42
}
```

Note: JSON keys are form element IDs (snake_case). All custom fields present on the FormRow are included automatically via `getCustomProperties().keySet()` iteration. Empty values are returned as `""`.

**Datalist view mappings** (how each datalist uses this endpoint):

| Datalist | Filter |
|---|---|
| Enrichment Workspace | `filter=status!=superseded,status!=confirmed` (or no filter — SQL WHERE handles exclusion) |
| Ready for Posting | `filter=status=ready` |
| Split/Merge History | `filter=origin=split` or `filter=origin=merge` |

**Error Responses**:
- **400**: Invalid filter syntax, unknown sort field
- **500**: Database error

---

### 5.3 GET /records/{id}

**Purpose**: Load a single record by primary key. Returns all form fields dynamically.

**Request**:
```
GET /records/ENR-042
```

**Response (200 OK)**:
```json
{
  "id": "ENR-042",
  "dateCreated": 1737936000000,
  "dateModified": 1737936000000,
  "createdBy": "admin",
  "modifiedBy": "admin",
  "version": 3,
  "source_type": "bank",
  "origin": "pipeline",
  "transaction_date": "2026-01-15",
  "internal_type": "SEC_BUY",
  "original_amount": "50000.00",
  "fee_amount": "125.00",
  "total_amount": "50125.00",
  "validated_currency": "EUR",
  "customer_code": "CUST001",
  "resolved_asset_id": "AAPL",
  "asset_category": "EQ",
  "counterparty_short_code": "LHV",
  "debit_credit": "D",
  "status": "enriched",
  "...": "(all other form fields)"
}
```

The `version` field is included for optimistic locking — the client must send it back in PUT requests.

**Error Responses**:
- **404**: Record not found

---

### 5.4 PUT /records/{id}

**Purpose**: Partial update of a single record. Only fields present in the request body are modified. Includes optimistic locking via version check.

**Request**:
```json
PUT /records/ENR-042
{
  "version": 3,
  "customer_code": "CUST002",
  "resolved_asset_id": "MSFT",
  "description": "Corrected customer and asset assignment"
}
```

**Behaviour**:

1. Load current record from database.
2. **Version check**: If `request.version != current.version`, return 409 Conflict.
3. Apply only the fields present in the request body to the FormRow.
4. **Auto-status adjustment**: If the record's current status is `enriched` and any field was changed, the status transitions to `adjusted` automatically (mirrors the PostProcessor logic from UX Interaction Layer §7.3).
5. **Confidence overrides**: If `internal_type` changed, set `type_confidence` to `manual_override` and clear `matched_rule_id`. If `resolved_customer_id` changed, set `customer_match_method` to `manual_override`. If `counterparty_id` changed, set `counterparty_source` to `manual_override`. These field IDs are read from the validationConfig, not hardcoded.
6. Increment `version` by 1.
7. Save via FormDataDao.

**Response (200 OK)**:
```json
{
  "id": "ENR-042",
  "version": 4,
  "status": "adjusted",
  "modifiedBy": "analyst1",
  "dateModified": 1737936500000,
  "...": "(full record with all fields)"
}
```

**Error Responses**:
- **404**: Record not found
- **409**: Version mismatch — `{"error": "VERSION_CONFLICT", "message": "Record was modified by another process. Current version: 4, your version: 3. Please reload and retry."}`
- **400**: Attempted to update a record with status `confirmed` or `superseded`

---

### 5.5 POST /records/{id}/status

**Purpose**: Transition a single record's status. Delegates to `StatusManager.transition()` from gam-framework (§3.5).

**Request**:
```json
POST /records/ENR-042/status
{
  "targetStatus": "ready",
  "reason": "Reviewed and approved"
}
```

The `reason` field is passed to StatusManager for audit logging. Optional for most transitions.

**Implementation**: The plugin resolves `targetStatus` to a `Status` enum value via `Status.fromCode()`, then calls `StatusManager.transition(dao, EntityType.ENRICHMENT, id, targetStatus, "enrichment-api", reason)`. The StatusManager validates the transition against its authoritative TRANSITIONS map, writes the new status, and creates an audit entry. If the transition is invalid, `InvalidTransitionException` is caught and mapped to a 400 response with valid target statuses from `StatusManager.getValidTransitions()`.

**Common transitions triggered by the UI**:

| Current Status | Target Status | Action Name | Notes |
|---|---|---|---|
| enriched, adjusted, paired, in_review | ready | Mark as Ready | — |
| ready | enriched | Return to Workspace | Returns record to editing state |
| error, manual_review | new | Reprocess | Resets for pipeline re-run |
| enriched | in_review | Begin Review | (system/implicit) |

**Response (200 OK)**:
```json
{
  "id": "ENR-042",
  "previousStatus": "enriched",
  "newStatus": "ready",
  "modifiedBy": "analyst1",
  "ms": 8
}
```

**Error Responses**:
- **400**: Invalid transition — `{"error": "INVALID_TRANSITION", "message": "Cannot transition from 'confirmed' to 'ready'. Valid targets: [adjusted (via revoke)]", "validTransitions": ["adjusted"]}`
- **404**: Record not found

---

### 5.6 POST /records/status (Batch)

**Purpose**: Apply the same status transition to multiple records in one call. Each record's transition is delegated to `StatusManager.transition()` individually.

**Request**:
```json
POST /records/status
{
  "recordIds": ["ENR-042", "ENR-043", "ENR-044"],
  "targetStatus": "ready"
}
```

**Behaviour**: Each record is validated independently via StatusManager. Records that cannot transition (InvalidTransitionException) are reported in the `failed` array but do not block others.

**Response (200 OK)**:
```json
{
  "succeeded": [
    {"id": "ENR-042", "previousStatus": "enriched", "newStatus": "ready"},
    {"id": "ENR-043", "previousStatus": "adjusted", "newStatus": "ready"}
  ],
  "failed": [
    {"id": "ENR-044", "currentStatus": "confirmed", "error": "Cannot transition from 'confirmed' to 'ready'"}
  ],
  "ms": 15
}
```

---

### 5.7 DELETE /records/{id}

**Purpose**: Delete a record. Only allowed for records with status ∈ {`new`, `error`, `manual_review`}.

**Request**:
```
DELETE /records/ENR-099
```

**Validation**: If the record's status is not in the allowed set, return 400.

**Response (204 No Content)**: Empty body on success.

**Error Responses**:
- **400**: `{"error": "DELETE_NOT_ALLOWED", "message": "Cannot delete record with status 'enriched'. Deletion is only allowed for: new, error, manual_review."}`
- **404**: Record not found

---

### 5.8 POST /records/confirm

**Purpose**: The central operation. Validates selected F01.05 records, computes reconciliation, and transitions them to `CONFIRMED` status. Confirmed records are then available for pickup by the downstream posting plugin.

**Request**:
```json
POST /records/confirm
{
  "recordIds": ["ENR-001", "ENR-002", "ENR-005", "ENR-010"],
  "allowPartial": true
}
```

`allowPartial`: If true, valid records proceed even if some fail validation (partial confirmation). If false, all records must pass or the entire batch is rejected.

**Execution steps**:

**Step 1 — Status filtering**: Only records with `status=ready` are accepted. Others are returned in `skipped` array.

**Step 2 — Per-record validation**: Each record is validated against the `validationConfig` rules (§2.2):

- Check all `requiredFields` are non-null and non-empty.
- Evaluate each `conditionalRequirements` entry: if the condition matches, check its required fields.
- Collect all errors per record. If any record fails and `allowPartial=false`, return immediately with validation errors.

**Step 3 — Reconciliation computation** (inline, not a separate call):

For each unique `statementId` in the batch, grouped by currency (field IDs from `reconciliation` config):

```
input_total = SUM(sourceTables[0].amountField) + SUM(sourceTables[1].amountField)
              for rows WHERE sourceTables[n].statementField = statementId
              grouped by sourceTables[n].currencyField

manual_adjustment = SUM(amountField) FROM F01.05
                    WHERE originField = manualOriginValue
                    AND statusField NOT IN ('superseded')
                    AND statementField = statementId
                    grouped by currencyField

adjusted_input = input_total + manual_adjustment

output_total = SUM(amountField) FROM F01.05
               WHERE statusField = 'confirmed'
               AND statementField = statementId
               grouped by currencyField
             + SUM(amountField) FROM current batch
               grouped by currencyField

remaining = SUM(amountField) FROM F01.05
            WHERE statusField NOT IN ('superseded', 'confirmed')
            AND statementField = statementId
            AND id NOT IN (current batch IDs)
            grouped by currencyField

discrepancy = adjusted_input - output_total - remaining

isFinalConfirmation = (remaining == 0 for all currencies)
```

Tolerance is checked per currency from `reconciliation.tolerance` config. MVP: reconciliation is informational only (warning, never blocking).

**Step 4 — Status transition** (all-or-nothing transaction):

For each valid F01.05 record:
1. Set audit fields from `confirmation` config: `confirmedByField` = current username, `confirmedAtField` = ISO timestamp.
2. Transition F01.05 to `CONFIRMED` via `StatusManager.transition(dao, EntityType.ENRICHMENT, enrichmentId, Status.CONFIRMED, "enrichment-api", "Confirmed for posting")`.

If any transition fails, the entire batch rolls back. No partial confirmations (unless `allowPartial=true`).

Once records reach `CONFIRMED` status, they leave this plugin's scope. A downstream posting plugin picks them up and creates F01.06 records.

**Response (200 OK)**:
```json
{
  "confirmed": 37,
  "skipped": [
    {"id": "ENR-010", "reason": "status is 'enriched', not 'ready'"}
  ],
  "validationErrors": [
    {"id": "ENR-005", "errors": ["Missing customer_code", "Missing fx_rate_to_eur (validated_currency=USD)"]}
  ],
  "reconciliation": {
    "statementId": "STM-2024-07",
    "isFinalConfirmation": false,
    "currencies": [
      {
        "currency": "EUR",
        "sourceInput": 45230.00,
        "manualAdj": 0.00,
        "adjustedInput": 45230.00,
        "output": 38730.00,
        "remaining": 6500.00,
        "discrepancy": 0.00,
        "tolerance": 0.02,
        "withinTolerance": true
      },
      {
        "currency": "USD",
        "sourceInput": 125400.00,
        "manualAdj": 600.00,
        "adjustedInput": 126000.00,
        "output": 100000.00,
        "remaining": 26000.00,
        "discrepancy": 0.00,
        "tolerance": 0.02,
        "withinTolerance": true
      }
    ]
  },
  "ms": 342
}
```

**Error Responses**:
- **400**: No valid records to confirm (all skipped or failed validation with `allowPartial=false`)
- **500**: Transaction rollback — `{"error": "BATCH_FAILED", "message": "Confirmation failed: [error]. No records were changed."}`

**Edge cases** (from UX Interaction Layer §1.6, §6.7):
- Mid-batch failure: entire transaction rolls back, no F01.05 status changed.
- Duplicate prevention: records already at `confirmed` status are silently skipped (idempotent).
- Large batches: recommended max 100 records per request, timeout set to 60 seconds.

---

### 5.9 POST /records/{id}/split

**Purpose**: Split a single F01.05 record into N child records with allocated amounts.

**Request**:
```json
POST /records/ENR-042/split
{
  "allocations": [
    {"customer_code": "CUST001", "original_amount": 50000.00, "fee_amount": 125.00},
    {"customer_code": "CUST002", "original_amount": 30000.00, "fee_amount": 75.00},
    {"customer_code": "CUST003", "original_amount": 20000.00, "fee_amount": 50.00}
  ]
}
```

**Validation**:

| Rule | Check | Error |
|---|---|---|
| Parent status | Must be ∈ {enriched, adjusted, in_review, ready} | "Record status '{status}' does not allow split" |
| Minimum allocations | ≥ 2 rows | "Split requires at least 2 allocations" |
| Amount sum | `SUM(original_amount) = parent.original_amount` (±0.01 tolerance) | "Amounts do not sum to source. Remaining: X.XX" |
| Fee sum | `SUM(fee_amount) = parent.fee_amount` | Warning only — auto-adjust last row for rounding |
| Customer required | Each allocation has non-empty customer_code (field ID from `splitMerge.customerField` config) | "Row N: customer is required" |

**Behaviour** (all-or-nothing transaction):

1. Load parent record.
2. Validate (see above). Auto-assign rounding remainder to last allocation row.
3. Create N child F01.05 records. For each child:
   - Copy all parent fields dynamically (iterate parent FormRow keys).
   - Override from allocation: `customerField`, `amountField`, `feeField`.
   - Compute: `totalField` = amount + fee, `eurAmountField` = total × parent's `fxRateField`.
   - Set lineage: `originField`=`split`, `parentIdField`=parent.id, `groupIdField`=parent.id, `sequenceField`=1..N, `lineageNoteField`="Split from {parent.id}: allocation to {customer_code}".
   - Transition child to `ENRICHED` via `StatusManager.transition(dao, EntityType.ENRICHMENT, childId, Status.ENRICHED, "enrichment-api", "Split child from " + parentId)`.
4. Transition parent to `SUPERSEDED` via `StatusManager.transition(dao, EntityType.ENRICHMENT, parentId, Status.SUPERSEDED, "enrichment-api", "Split into " + N + " children")`.

**Response (200 OK)**:
```json
{
  "parentId": "ENR-042",
  "parentStatus": "superseded",
  "children": [
    {"id": "ENR-042-S1", "customer_code": "CUST001", "original_amount": 50000.00, "split_sequence": 1},
    {"id": "ENR-042-S2", "customer_code": "CUST002", "original_amount": 30000.00, "split_sequence": 2},
    {"id": "ENR-042-S3", "customer_code": "CUST003", "original_amount": 20000.00, "split_sequence": 3}
  ],
  "ms": 45
}
```

**Error Responses**:
- **400**: Validation failure (amounts don't sum, missing customer, wrong status)
- **404**: Parent record not found
- **500**: Transaction rollback

---

### 5.10 POST /records/merge

**Purpose**: Merge multiple F01.05 records into a single combined record.

**Request**:
```json
POST /records/merge
{
  "recordIds": ["ENR-101", "ENR-102", "ENR-103"],
  "mergedFields": {
    "internal_type": "SEC_BUY",
    "customer_code": "CUST001",
    "resolved_asset_id": "AAPL",
    "counterparty_short_code": "LHV",
    "debit_credit": "D",
    "description": "Merged: 3 SEC_BUY orders for AAPL",
    "transaction_date": "2024-07-15",
    "fx_rate_to_eur": "1.0842"
  }
}
```

**Eligibility validation** (from UX Interaction Layer §3.2):

| Rule | Check | Error |
|---|---|---|
| Same statement | All records share `statementField` value | "Cannot merge records from different statements" |
| Same source type | All records share `source_type` | "Cannot merge bank and securities records" |
| Same currency | All records share `currencyField` value | "Cannot merge records with different currencies" |
| Status eligibility | All statuses ∈ {enriched, adjusted, in_review} | "Record {id} has status '{status}' — only enriched, adjusted, or in_review may be merged" |
| Minimum count | ≥ 2 records | "Merge requires at least 2 records" |

**Field resolution for the merged record**:

| Field | Resolution | Overridable via mergedFields |
|---|---|---|
| `amountField` (original_amount) | SUM of all sources | No (computed) |
| `feeField` (fee_amount) | SUM of all sources | No (computed) |
| `totalField` (total_amount) | SUM of all sources | No (computed) |
| `eurAmountField` (base_amount_eur) | SUM of all sources | No (computed) |
| `internal_type` | Pre-filled if all sources unanimous; blank if mixed | Yes |
| `customerField` | Pre-filled if unanimous; blank if mixed | Yes |
| `resolved_asset_id` | Pre-filled if unanimous; blank if mixed | Yes |
| `counterparty_short_code` | Pre-filled if unanimous; blank if mixed | Yes |
| `debit_credit` | Pre-filled if unanimous; blank if mixed | Yes |
| `transaction_date` | Earliest date among sources | Yes |
| `settlement_date` | Latest date among sources | Yes |
| `description` | Auto: "Merged: N {type} orders for {asset}" | Yes |
| `validated_currency` | From sources (enforced same) | No |
| Other fields | From first source record | Yes |

**Behaviour** (all-or-nothing transaction):

1. Load all source records. Validate eligibility.
2. Create 1 merged F01.05 record with computed + resolved + overridden fields.
3. Set lineage: `originField`=`merge`, `groupIdField`=new UUID, `lineageNoteField`="Merged from: {id1}, {id2}, {id3}". Transition merged record to `ENRICHED` via `StatusManager.transition(dao, EntityType.ENRICHMENT, mergedId, Status.ENRICHED, "enrichment-api", "Merged from " + sourceIds)`.
4. Transition all source records to `SUPERSEDED` via `StatusManager.transition()` for each, with `groupIdField`=same UUID.

**Response (200 OK)**:
```json
{
  "mergedId": "ENR-M001",
  "sourceIds": ["ENR-101", "ENR-102", "ENR-103"],
  "original_amount": 23000.00,
  "fee_amount": 57.50,
  "total_amount": 23057.50,
  "ms": 38
}
```

**Error Responses**:
- **400**: Eligibility failure (different statements, currencies, or statuses)
- **404**: One or more source records not found

**Merge validation on confirm** (from UX Interaction Layer §3.3): The merge dialog requires `internal_type`, `customer_code`, and `debit_credit` to be set. If missing from `mergedFields` and sources are not unanimous, return 400.

---

### 5.11 GET /reconciliation/{statementId}

**Purpose**: Standalone reconciliation query for a statement. Same computation as the reconciliation step in POST /records/confirm, but without a confirmation batch. Useful for the dashboard and ad-hoc checks.

**Request**:
```
GET /reconciliation/STM-2024-07
```

**Response (200 OK)**:
```json
{
  "statementId": "STM-2024-07",
  "isFinalConfirmation": false,
  "currencies": [
    {
      "currency": "EUR",
      "sourceInput": 45230.00,
      "manualAdj": 0.00,
      "adjustedInput": 45230.00,
      "output": 38730.00,
      "remaining": 6500.00,
      "discrepancy": 0.00,
      "tolerance": 0.02,
      "withinTolerance": true
    },
    {
      "currency": "USD",
      "sourceInput": 125400.00,
      "manualAdj": 600.00,
      "adjustedInput": 126000.00,
      "output": 100000.00,
      "remaining": 26000.00,
      "discrepancy": 0.00,
      "tolerance": 0.02,
      "withinTolerance": true
    }
  ],
  "ms": 28
}
```

`isFinalConfirmation` is false here because no batch is being confirmed. It indicates whether all F01.05 records for this statement are either `superseded` or `confirmed` (i.e., no active work remains).

**Error Responses**:
- **404**: No records found for the given statementId

---

### 5.12 GET /summary

**Purpose**: Dashboard endpoint. Returns statement-level aggregated counts for the Enrichment Summary panel (UX Interaction Layer §5.4).

**Request**:
```
GET /summary
```

**Response (200 OK)**:
```json
{
  "statements": [
    {
      "statementId": "STM-2024-06",
      "total": 45,
      "new": 0,
      "working": 5,
      "ready": 12,
      "confirmed": 28,
      "error": 0
    },
    {
      "statementId": "STM-2024-07",
      "total": 52,
      "new": 3,
      "working": 8,
      "ready": 15,
      "confirmed": 22,
      "error": 4
    },
    {
      "statementId": "STM-2024-08",
      "total": 38,
      "new": 38,
      "working": 0,
      "ready": 0,
      "confirmed": 0,
      "error": 0
    }
  ],
  "ms": 15
}
```

**Count definitions** (matching UX Interaction Layer §5.4):

| Field | Definition |
|---|---|
| total | COUNT where status NOT IN ('superseded') |
| new | COUNT where status = 'new' |
| working | COUNT where status IN ('enriched', 'adjusted', 'in_review', 'paired', 'manual_review') |
| ready | COUNT where status = 'ready' |
| confirmed | COUNT where status = 'confirmed' |
| error | COUNT where status = 'error' |

The query uses the `statementField` and `statusField` from validationConfig. Statement reference labels can be resolved by joining against the statement table if needed.

---

## 7. Data Models & Field Definitions

### 7.1 F01.05 Enrichment Transaction Form

Standard fields (from form design):

| Field ID | Type | Nullable | Constraints | Description |
|---|---|---|---|---|
| **id** | String | No | PK | Unique identifier, auto-generated (e.g., ENR-001). |
| **internal_type** | String | No | Enum | Transaction type: SEC_BUY, SEC_SELL, BOND_BUY, CASH_IN, CASH_OUT, FEE, etc. |
| **original_amount** | Decimal | No | > 0 | Transaction amount before fees. |
| **fee_amount** | Decimal | Yes | >= 0 | Associated fees. |
| **total_amount** | Decimal | No | > 0 | original_amount + fee_amount. |
| **validated_currency** | String | No | 3-char code | ISO 4217 currency code (EUR, USD, GBP, etc.). |
| **customer_code** | String | No | — | Customer identifier. |
| **debit_credit** | String | No | D \| C | Debit or Credit indicator. |
| **resolved_asset_id** | String | Yes | — | Security ISIN, ticker, or asset identifier. Required for SEC_* and BOND_*. |
| **counterparty_short_code** | String | Yes | — | Counterparty identifier. Required for most types. |
| **fx_rate_to_eur** | Decimal | Yes | > 0 | FX rate to EUR. Required if validated_currency != EUR. |
| **base_amount_eur** | Decimal | Yes | — | Amount in EUR (total_amount * fx_rate_to_eur). |
| **statement_id** | String | No | FK | Reference to statement. |
| **origin** | String | No | system \| manual \| split \| merge | Record origin. |
| **status** | String | No | (see §4) | Current status in lifecycle. |
| **parent_enrichment_id** | String | Yes | FK | ID of parent if record is a split child. |
| **group_id** | String | Yes | — | UUID grouping all records in a split or merge operation. |
| **split_sequence** | Integer | Yes | > 0 | Sequence number if record is a split child. |
| **lineage_note** | String | Yes | — | Human-readable note on record origin (e.g., "Split from ENR-042"). |
| **transaction_date** | Date | No | — | Transaction date. |
| **description** | String | Yes | — | Narrative description. |
| **asset_category** | String | Yes | — | Security category (EQUITY, BOND, DERIVATIVE, etc.). Required if resolved_asset_id is set. |
| **dateCreated** | Timestamp | No | Auto | Record creation time. |
| **dateModified** | Timestamp | No | Auto | Last modification time. |
| **createdBy** | String | No | — | User ID who created record. |
| **modifiedBy** | String | No | — | User ID who last modified record. |
| **version** | Integer | No | Auto | Optimistic locking version counter. |

---

## 8. Error Handling & Status Codes

### 8.1 HTTP Status Codes

| Code | Meaning | Example Scenario |
|---|---|---|
| **200 OK** | Request succeeded | GET /records, PUT /records/{id} |
| **204 No Content** | Request succeeded, no response body | DELETE /records/{id} |
| **400 Bad Request** | Invalid input, validation error | Invalid filter syntax, missing required field |
| **404 Not Found** | Resource does not exist | Record ID not found, statement not found |
| **409 Conflict** | Version mismatch (optimistic locking) | PUT /records/{id} with stale version |
| **500 Internal Server Error** | Server error | Database connection failure, unhandled exception |

### 8.2 Error Response Format

All error responses follow a consistent format:

```json
{
  "error": "Short error code or title",
  "message": "Detailed description of the error",
  "details": {
    "field": "value",
    "context": "additional info"
  }
}
```

---

## 9. Validation Rules

### 9.1 Configuration-Driven Validation

All validation rules are defined in the `validationConfig` JSON property (§2.2). The plugin does NOT hardcode any rules.

**Rule Types**:

1. **Required Fields**: Unconditionally required.
2. **Conditional Requirements**: Required if a condition evaluates to true.
3. **Tolerance Ranges**: Numeric values must fall within tolerance (e.g., ±0.02 for amounts).
4. **Pattern Matching**: Field values must match or exclude a regex pattern.

### 9.2 Condition Evaluation

Conditions support the following operators:

```json
{
  "field": "internal_type",
  "matchPattern": "SEC_.*|BOND_.*"  // Field must match regex
}
```

```json
{
  "field": "validated_currency",
  "notEquals": "EUR"  // Field must NOT equal value
}
```

```json
{
  "field": "resolved_asset_id",
  "isNotEmpty": true  // Field must have a value
}
```

---

## 10. Authentication & Authorization

The plugin uses Joget API Builder's built-in authentication. It does NOT implement its own auth logic.

**Authentication**: Joget API Builder authenticates requests via two HTTP headers:

```
api_id: <API_BUILDER_ID>    (e.g., "API-xxxx" — the API Builder ID from Joget admin)
api_key: <API_KEY>           (the API Key from API Builder Settings)
```

There is no Bearer token or OAuth flow. The `api_id` and `api_key` are configured in the API Builder UI and passed as request headers by the workspace UI plugin.

**Authorization**:

1. User identity is available via the Joget security context (the logged-in Joget user).
2. Current user ID is retrieved via `WorkflowUserManager.getCurrentUsername()`.
3. Role-based access control (RBAC) is enforced by Joget's userview and form permission rules, not by this plugin.

**Plugin Responsibility**:

- Use current user ID for audit fields: `confirmed_by`, `createdBy`, `modifiedBy`.
- Capture current timestamp for all time-sensitive operations.

---

## 11. Performance Considerations

### 11.1 Pagination

- Default page size: 50 records.
- Maximum page size: 200 records (configurable via maxPageSize property).
- Offset-based pagination (page=1, pageSize=50 starts at offset 0).

### 11.2 Indexing

Recommended database indexes on F01.05:

```sql
CREATE INDEX idx_statement_id ON trx_enrichment(statement_id);
CREATE INDEX idx_status ON trx_enrichment(status);
CREATE INDEX idx_origin ON trx_enrichment(origin);
CREATE INDEX idx_statement_currency ON trx_enrichment(statement_id, validated_currency);
CREATE INDEX idx_date_created ON trx_enrichment(dateCreated DESC);
```

### 11.3 Batch Operation Limits

- **POST /records/confirm**: Tested up to 100 records per request. Larger batches may require pagination.
- **POST /records/merge**: Maximum 20 source records per merge to prevent runaway queries.

---

## 12. Version History

| Version | Date | Changes |
|---|---|---|
| **1.0.0** | 2024-07-15 | Initial release. F01.05-only scope. Status lifecycle via gam-framework, split/merge, confirm, reconciliation. Configuration-driven validation. Dynamic field iteration. |

---

## 13. Appendix: curl Examples

### 13.1 Health Check

```bash
curl -X GET http://localhost:8080/jw/api/enrichmentService/health \
  -H "api_id: <API_BUILDER_ID>" \
  -H "api_key: <API_KEY>"
```

### 13.2 List All Records (Paginated)

```bash
curl -X GET "http://localhost:8080/jw/api/enrichmentService/records?page=1&pageSize=50" \
  -H "api_id: <API_BUILDER_ID>" \
  -H "api_key: <API_KEY>"
```

### 13.3 Filter by Status

```bash
curl -X GET "http://localhost:8080/jw/api/enrichmentService/records?filter=status=ready&pageSize=100" \
  -H "api_id: <API_BUILDER_ID>" \
  -H "api_key: <API_KEY>"
```

### 13.4 Search by Customer Code

```bash
curl -X GET "http://localhost:8080/jw/api/enrichmentService/records?search=customer_code:CUST001&pageSize=50" \
  -H "api_id: <API_BUILDER_ID>" \
  -H "api_key: <API_KEY>"
```

### 13.5 Confirm Batch for Posting

```bash
curl -X POST http://localhost:8080/jw/api/enrichmentService/records/confirm \
  -H "api_id: <API_BUILDER_ID>" \
  -H "api_key: <API_KEY>" \
  -H "Content-Type: application/json" \
  -d '{
    "recordIds": ["ENR-001", "ENR-002", "ENR-005"],
    "allowPartial": true
  }'
```

### 13.6 Split Record

```bash
curl -X POST http://localhost:8080/jw/api/enrichmentService/records/ENR-042/split \
  -H "api_id: <API_BUILDER_ID>" \
  -H "api_key: <API_KEY>" \
  -H "Content-Type: application/json" \
  -d '{
    "allocations": [
      {"customer_code": "CUST001", "original_amount": 50000, "fee_amount": 125},
      {"customer_code": "CUST002", "original_amount": 50000, "fee_amount": 125}
    ]
  }'
```

### 13.7 Merge Records

```bash
curl -X POST http://localhost:8080/jw/api/enrichmentService/records/merge \
  -H "api_id: <API_BUILDER_ID>" \
  -H "api_key: <API_KEY>" \
  -H "Content-Type: application/json" \
  -d '{
    "recordIds": ["ENR-101", "ENR-102"],
    "mergedFields": {
      "customer_code": "CUST001",
      "description": "Merged order"
    }
  }'
```

### 13.8 Get Reconciliation

```bash
curl -X GET http://localhost:8080/jw/api/enrichmentService/reconciliation/STM-2024-07 \
  -H "api_id: <API_BUILDER_ID>" \
  -H "api_key: <API_KEY>"
```

### 13.9 Get Summary Dashboard

```bash
curl -X GET http://localhost:8080/jw/api/enrichmentService/summary \
  -H "api_id: <API_BUILDER_ID>" \
  -H "api_key: <API_KEY>"
```

---

## 14. UX Traceability Matrix

This table maps each UX requirement (from the data layer and interaction layer specifications) to the API endpoint(s) that support it.

| UX Requirement | Source Doc | API Endpoint | Notes |
|---|---|---|---|
| Enrichment Workspace datalist | Data Layer §1 | GET /records?filter=status!=superseded,status!=confirmed | Lists active enrichment records |
| Ready for Posting datalist | Data Layer §2.1 | GET /records?filter=status=ready | Lists records approved for posting |
| Confirmed Records datalist | Data Layer §2.2 | GET /records?filter=status=confirmed | Lists confirmed records awaiting downstream pickup |
| Split/Merge History datalist | Data Layer §2.4 | GET /records?filter=origin=split (or origin=merge) | Lists child records from split/merge |
| Statement Summary dashboard | Interaction Layer §5.4 | GET /summary | Aggregated counts by statement |
| Confirm batch action | Interaction Layer §1 | POST /records/confirm | Validate + transition to CONFIRMED |
| Split action | Interaction Layer §2 | POST /records/{id}/split | Create N child records |
| Merge action | Interaction Layer §3 | POST /records/merge | Create 1 merged record from N sources |
| Mark as Ready | Data Layer §1.7 | POST /records/status (targetStatus=ready) | Transition enriched → ready |
| Return to Workspace | Data Layer §2.1 batch actions | POST /records/status (targetStatus=enriched) | Transition ready → enriched |
| Reprocess | Data Layer §1.7 | POST /records/status (targetStatus=new) | Transition error → new |
| Delete record | Data Layer §1.7 | DELETE /records/{id} | Only if status ∈ {new, error, manual_review} |
| Manual entry creation | Interaction Layer §6.5 | PUT /records (new record with origin=manual) | Create via form + API update |
| Optimistic locking | Interaction Layer §6.1 | PUT /records/{id} with version check | Prevents concurrent updates |
| Statement→Workspace linking | Interaction Layer §5.2 | GET /records?filter=statement_id=X | Links statement to enrichment records |
| Reconciliation panel | Interaction Layer §1.4 | Inline in POST /records/confirm response | Returned as part of confirm response |
| Standalone reconciliation | Architecture §5.2 | GET /reconciliation/{statementId} | Query without confirmation |

---

## 15. Design Patterns & Best Practices

### 15.1 FormDataDao Usage

Always use FormDataDao for reads and standard updates to leverage Joget's transaction management and auditing:

```java
FormRow row = formDataDao.loadFormRow(tableName, id);
formDataDao.insertFormData(row);
formDataDao.updateFormData(row);
formDataDao.deleteFormData(row);
```

Never access the database directly for these operations.

### 15.2 Dynamic Field Iteration

Iterate form fields dynamically to ensure compatibility with form structure changes:

```java
for (String fieldId : row.getCustomProperties().keySet()) {
  Object value = row.getProperty(fieldId);
  // Process field
}
```

Do NOT maintain a static list of expected fields.

### 15.3 Empty String Handling

FormDataDao treats empty string (`""`) and null distinctly. Always use empty string for absence checks:

```java
formDataDao.loadFormDataList(tableName, null, "field_id = ''", null, null, null, null);
```

### 15.4 Batch Operations with Transactions

For all-or-nothing batch operations, use direct JDBC:

```java
Connection conn = workflowManager.getConnection();
try {
  conn.setAutoCommit(false);
  // Execute DML statements
  conn.commit();
} catch (Exception e) {
  conn.rollback();
  throw e;
}
```

### 15.5 Version Checking

Always check and increment version for updates to prevent stale writes:

```java
if (request.version != current.version) {
  throw new ConflictException("Version mismatch");
}
row.setProperty("version", current.version + 1);
```

### 15.6 Status Transitions via gam-framework

Never set status strings directly. Always delegate to StatusManager:

```java
// ❌ WRONG — bypasses validation, audit logging, and state machine
row.setProperty("status", "ready");

// ✅ CORRECT — validates, writes, and audits in one call
StatusManager manager = new StatusManager();
FormDataDao dao = StatusManager.getFormDataDao();
manager.transition(dao, EntityType.ENRICHMENT, recordId,
    Status.READY, "enrichment-api", "Analyst approval");
```

For batch operations within a JDBC transaction, call `StatusManager.transition()` for each record individually. If any transition throws `InvalidTransitionException`, roll back the entire batch.

---

## 16. Known Limitations & Future Enhancements

### 16.1 Current Limitations

1. **Reconciliation is Informational**: Discrepancies are reported but do not block confirmation. This may change in Phase 8.

2. **No Audit Trail Endpoint**: The plugin does not expose audit history via API. However, all status transitions are logged to the `audit_log` table by gam-framework's StatusManager (entity_type, entity_id, from_status, to_status, triggered_by, reason, timestamp).

3. **Single Instance per Plugin**: The plugin is tied to a single primary table (tableName). Multi-tenant deployments require separate plugin instances.

4. **Max Batch Size**: Batch operations (confirm, split, merge) have soft limits to prevent performance degradation. No hard enforcement, but recommended limits are documented.

### 16.2 Planned Enhancements

1. **Phase 8: Reconciliation Blocking**: Discrepancies exceeding tolerance will block confirmation unless explicitly overridden by authorized users.

2. **Phase 9: Audit History Endpoint**: Expose full audit trail via GET /records/{id}/history, reading from gam-framework's `audit_log` table.

3. **Phase 10: Multi-Tenant Support**: Configure plugin to handle multiple statements/customers in isolation.

---

## 17. Glossary

| Term | Definition |
|---|---|
| **Enrichment** | Process of adding customer/asset/FX information to raw transaction data. |
| **Confirmation** | Final approval step that transitions records to CONFIRMED status for downstream pickup. |
| **Reconciliation** | Verification that input totals match output + remaining. |
| **Split** | Dividing one transaction into N smaller transactions. |
| **Merge** | Combining N transactions into one larger transaction. |
| **Revoke** | Canceling a downstream posting and returning the enrichment record to adjusted status. Handled by the posting plugin, not this plugin. |
| **Lineage** | Audit trail showing record origin (system, manual, split, merge). |
| **Group ID** | UUID grouping records from split or merge operations. |
| **Origin** | Field indicating where a record came from: system, manual, split, or merge. |
| **Statement** | Batch of transactions (e.g., daily statement from bank or broker). |

---

## 18. Contact & Support

**Plugin Owner**: [Enrichment API Team]
**Repository**: [GitHub/GitLab URL]
**Issue Tracking**: [Jira/Issue Board URL]
**Documentation**: [Wiki/Confluence URL]

---

**End of Specification**

Document Version: 1.0.0
Last Updated: 2024-07-15
Status: Production
