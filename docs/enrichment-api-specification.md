# Enrichment API v2.1.0 Specification

**Plugin**: enrichment-api v2.1.0
**Platform**: Joget DX 8.1.6 API Builder Plugin (ApiPluginAbstract)
**Scope**: F01.05 Manual Enrichment only (reads F01.03/F01.04 for reconciliation)
**Design Principle**: Configuration-driven, no hardcoding, dynamic field iteration, gam-framework for state management
**Last Updated**: 2026-03-08

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
    "debit_credit",
    "transaction_date",
    "settlement_date",
    "total_amount",
    "resolved_customer_id"
  ],

  "conditionalRequirements": [
    {
      "condition": {
        "field": "internal_type",
        "matchPattern": "SEC_.*|BOND_.*"
      },
      "requiredFields": ["resolved_asset_id", "asset_category"]
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
      "requiredFields": ["fx_rate_to_eur", "fx_rate_source"]
    },
    {
      "condition": {
        "field": "requires_eur_parallel",
        "equals": "yes"
      },
      "requiredFields": ["fx_rate_to_eur", "fx_rate_source"]
    },
    {
      "condition": {
        "field": "has_fee",
        "equals": "yes"
      },
      "requiredFields": ["fee_amount"]
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
  },

  "confidenceOverrides": [
    {
      "triggerField": "internal_type",
      "setFields": {
        "type_confidence": "high"
      },
      "clearFields": ["manual_override_reason"]
    }
  ]
}
```

#### Configuration Field Definitions

**baseCurrency**: The home/reporting currency (default: "EUR"). Used in reconciliation tolerance lookups and FX calculations.

**requiredFields**: List of F01.05 form element IDs that must be non-null and non-empty for confirmation or submission. For enrichment, these are the 9 mandatory fields verified during the POST /records/confirm operation:
- internal_type
- resolved_customer_id
- validated_currency
- customer_code
- debit_credit
- original_amount
- total_amount
- transaction_date
- settlement_date

**conditionalRequirements**: Array of condition+requiredFields pairs. Each condition evaluates one field using pattern matching or value comparison. If the condition is true, all fields in requiredFields become required for confirmation. Examples:
- If `internal_type` matches `SEC_.*|BOND_.*`: `resolved_asset_id` and `asset_category` required
- If `validated_currency` is not "EUR": `fx_rate_to_eur` and `fx_rate_source` required
- If `requires_eur_parallel` equals "yes": `fx_rate_to_eur` and `fx_rate_source` required
- If `has_fee` equals "yes": `fee_amount` required

Condition operators:
- `matchPattern`: Regex pattern (Java Pattern syntax). Field value must match.
- `excludePattern`: Regex pattern. Field value must NOT match.
- `notEquals`: Exact string comparison. Field value must differ.
- `equals`: Exact string comparison. Field value must match.
- `isNotEmpty`: Boolean. True if field must have a value.

**reconciliation**: Defines how to compute per-statement, per-currency balances.
- `amountField`: Form element ID in F01.05 containing the amount to sum.
- `currencyField`: Form element ID in F01.05 containing the currency.
- `statementField`: Form element ID in F01.05 linking to a statement ID.
- `originField`: Form element ID in F01.05 indicating whether record originated from manual entry or system-generated.
- `manualOriginValue`: String value of originField for manually-created records (e.g., "manual").
- `statusField`: Form element ID in F01.05 containing status.
- `sourceTables`: Array of source table definitions (F01.03, F01.04). Each has tableName, amountField, currencyField, and statementField.
- `tolerance`: Per-currency tolerance for reconciliation discrepancy. Use `_default` for unmapped currencies. Values are decimal (e.g., 0.02 = ±0.02).

**splitMerge**: Field ID mappings for split and merge operations. Maps form element IDs to database column names used in split/merge child record creation.

**confirmation**: Field ID mappings for audit fields set on the F01.05 record when confirmed (who confirmed it and when).

**confidenceOverrides**: Array of field-change triggers that automatically set other fields. When a listed `triggerField` changes, the plugin sets fields in `setFields` and clears fields in `clearFields`. Enables dynamic confidence scoring and automated field management.

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
   formDataDao.saveOrUpdate(null, tableName, rowSet);
   ```

2. **Batch Operations (all-or-nothing)**: Use direct JDBC with explicit transaction control.
   ```java
   Connection conn = JdbcHelper.getConnection();
   try {
     conn.setAutoCommit(false);
     // Execute multiple DML statements
     for (...) {
       JdbcHelper.insertRow(conn, tableName, childId, childFields);
       JdbcHelper.updateColumns(conn, tableName, parentId, updates);
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
        tableName,                   // e.g., "trxEnrichment" (actual form table name)
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

## 4. F01.05 Status Lifecycle & Business Rules

### 4.1 ENRICHMENT State Machine (from gam-framework)

```
NEW → PROCESSING
PROCESSING → ENRICHED | ERROR | MANUAL_REVIEW
ENRICHED → IN_REVIEW | ADJUSTED | READY | PAIRED | MANUAL_REVIEW | SUPERSEDED
IN_REVIEW → ADJUSTED | READY | ENRICHED | SUPERSEDED
ADJUSTED → READY | IN_REVIEW | ENRICHED | SUPERSEDED
READY → CONFIRMED | ENRICHED | IN_REVIEW | SUPERSEDED
PAIRED → READY | MANUAL_REVIEW
CONFIRMED (terminal within this plugin)
SUPERSEDED (terminal)
ERROR → NEW | MANUAL_REVIEW
MANUAL_REVIEW → NEW | ENRICHED | READY
```

**Source of truth**: The authoritative state machine is defined in `gam-framework` → `StatusManager.TRANSITIONS` for `EntityType.ENRICHMENT`. The enrichment-api plugin does not define its own transition rules — it delegates all transitions to `StatusManager.transition()`. If this diagram diverges from StatusManager, the StatusManager wins.

### 4.2 Status-Dependent API Operations

#### NEW
**Meaning**: Created but not yet processed through batch enrichment.

**Allowed Operations**:
- **DELETE** ✓ (allowed — deletable status)
- **PUT /records/{id}** ✓ (can edit fields)
- **POST /records/{id}/status** ✓ (can transition to PROCESSING or MANUAL_REVIEW)
- **SPLIT** ✗ (not splittable)
- **MERGE** ✗ (not mergeable)

**Status Transitions**: NEW → PROCESSING | MANUAL_REVIEW

---

#### PROCESSING
**Meaning**: Batch enrichment in progress (system only, not user-editable).

**Allowed Operations**:
- **GET** ✓ (read-only during processing)
- **PUT /records/{id}** — limited (processing_notes only)
- **DELETE** ✗ (cannot delete)
- **POST /records/{id}/status** ✓ (can transition to ENRICHED, ERROR, or MANUAL_REVIEW)
- **SPLIT** ✗
- **MERGE** ✗

**Status Transitions**: PROCESSING → ENRICHED | ERROR | MANUAL_REVIEW

---

#### ENRICHED
**Meaning**: System-level enrichment complete; awaiting human review or ready for transition.

**Allowed Operations**:
- **GET** ✓
- **PUT /records/{id}** ✓ (edit fields; triggers auto-transition to ADJUSTED)
- **DELETE** ✓ (allowed if returning from manual review — in this flow, user marks it NEW first, then can delete)
- **POST /records/{id}/status** ✓ (can transition to IN_REVIEW, ADJUSTED, READY, PAIRED, MANUAL_REVIEW, or SUPERSEDED)
- **SPLIT** ✓ (splittable status)
- **MERGE** ✓ (mergeable status)
- **Confirm** ✗ (requires READY status)

**Auto-Transition on PUT**: When a record in ENRICHED status is edited via PUT /records/{id}, the plugin automatically transitions it to ADJUSTED status (fires confidence override rules).

**Status Transitions**: ENRICHED → IN_REVIEW | ADJUSTED | READY | PAIRED | MANUAL_REVIEW | SUPERSEDED

---

#### IN_REVIEW
**Meaning**: Customer is actively reviewing the record.

**Allowed Operations**:
- **GET** ✓
- **PUT /records/{id}** ✓ (edit fields; stays in IN_REVIEW)
- **DELETE** ✗ (cannot delete during review)
- **POST /records/{id}/status** ✓ (can transition to ADJUSTED, READY, or back to ENRICHED)
- **SPLIT** ✓ (splittable status)
- **MERGE** ✓ (mergeable status)

**Status Transitions**: IN_REVIEW → ADJUSTED | READY | ENRICHED

---

#### ADJUSTED
**Meaning**: Customer has made modifications to fields; awaiting final approval.

**Allowed Operations**:
- **GET** ✓
- **PUT /records/{id}** ✓ (edit fields; stays in ADJUSTED)
- **DELETE** ✗
- **POST /records/{id}/status** ✓ (can transition to READY, IN_REVIEW, or back to ENRICHED)
- **SPLIT** ✓ (splittable status)
- **MERGE** ✓ (mergeable status)

**Status Transitions**: ADJUSTED → READY | IN_REVIEW | ENRICHED

---

#### READY
**Meaning**: Record has been approved and is ready for confirmation processing.

**Allowed Operations**:
- **GET** ✓
- **PUT /records/{id}** ✓ (limited editing — only specific fields, typically notes/metadata)
- **DELETE** ✗ (cannot delete)
- **POST /records/{id}/status** ✓ (can transition to CONFIRMED, ENRICHED, or IN_REVIEW)
- **SPLIT** ✓ (splittable status — splits a ready record)
- **MERGE** ✗ (NOT mergeable — ready records cannot be merged; only ENRICHED, ADJUSTED, IN_REVIEW can merge)
- **POST /records/confirm** ✓ (confirmation endpoint filters for status=ready)

**Status Transitions**: READY → CONFIRMED | ENRICHED | IN_REVIEW

---

#### PAIRED
**Meaning**: Record has been paired with another record for combined processing.

**Allowed Operations**:
- **GET** ✓
- **PUT /records/{id}** ✓ (limited editing)
- **DELETE** ✗
- **POST /records/{id}/status** ✓ (can transition to READY or MANUAL_REVIEW)
- **SPLIT** ✗
- **MERGE** ✗

**Status Transitions**: PAIRED → READY | MANUAL_REVIEW

---

#### MANUAL_REVIEW
**Meaning**: Requires analyst intervention due to validation failure or exception.

**Allowed Operations**:
- **GET** ✓
- **PUT /records/{id}** ✓ (edit fields)
- **DELETE** ✓ (allowed — deletable status)
- **POST /records/{id}/status** ✓ (can transition to NEW, ENRICHED, or READY)
- **SPLIT** ✗
- **MERGE** ✗

**Status Transitions**: MANUAL_REVIEW → NEW | ENRICHED | READY

---

#### ERROR
**Meaning**: Enrichment processing failed.

**Allowed Operations**:
- **GET** ✓
- **PUT /records/{id}** — limited (processing_notes only)
- **DELETE** ✓ (allowed — deletable status)
- **POST /records/{id}/status** ✓ (can transition to NEW or MANUAL_REVIEW via reprocess)
- **SPLIT** ✗
- **MERGE** ✗

**Status Transitions**: ERROR → NEW | MANUAL_REVIEW

---

#### CONFIRMED
**Meaning**: Record is ready for downstream posting. Terminal status within this plugin.

**Allowed Operations**:
- **GET** ✓ (read-only)
- **PUT** ✗
- **DELETE** ✗
- **POST /records/{id}/status** ✗ (terminal — no further transitions in enrichment)
- **SPLIT** ✗
- **MERGE** ✗

**Terminal Status**: Once a record reaches CONFIRMED, it leaves the enrichment plugin's scope. The downstream posting plugin may later revoke it back to ADJUSTED status if the posting is reversed.

**Status Transitions**: None (terminal)

---

#### SUPERSEDED
**Meaning**: Record has been replaced by a split or merge operation. Terminal status within this plugin.

**Allowed Operations**:
- **GET** ✓ (read-only — for lineage tracking)
- **PUT** ✗
- **DELETE** ✗
- **POST /records/{id}/status** ✗ (terminal)
- **SPLIT** ✗
- **MERGE** ✗

**Lineage Fields**: Records in SUPERSEDED status retain:
- `origin`: "split" or "merge"
- `parent_enrichment_id`: ID of the parent record (for split children)
- `group_id`: UUID linking all records in the split/merge operation
- `split_sequence`: Sequence number (for split children, 1..N)
- `lineage_note`: Human-readable explanation

**Status Transitions**: None (terminal)

---

### 4.3 Field Editability Matrix

The following 39 fields are editable via the enrichment-api in normal statuses:

**Transaction Core** (6 fields):
- transaction_date
- settlement_date
- debit_credit
- original_amount
- fee_amount
- total_amount

**Description** (1 field):
- description

**Classification** (3 fields):
- internal_type
- matched_rule_id
- type_confidence

**Currency & FX** (6 fields):
- validated_currency
- fx_rate_to_eur
- fx_rate_date
- fx_rate_source
- requires_eur_parallel
- base_amount_eur

**Resolved Entities** (8 fields):
- resolved_customer_id
- customer_code
- customer_display_name
- customer_match_method
- resolved_asset_id
- asset_category
- counterparty_id
- counterparty_short_code

**Fee** (2 fields):
- has_fee
- base_fee_eur

**Loan** (3 fields):
- loan_id
- loan_direction
- loan_resolution_method

**GL Override** (3 fields):
- gl_debit_override
- gl_credit_override
- gl_override_reason

**Pairing & Linking** (3 fields):
- pair_id
- acc_post_id
- source_reference

**Fund & Period** (2 fields):
- fund_allocation_status
- period_locked

**Lineage** (1 field):
- lineage_note

**Processing** (Always editable, even in terminal/restricted statuses):
- processing_notes

**Editability by Status**:

All 39 fields follow the same pattern except `processing_notes`:

| Status | Editable Fields | processing_notes |
|---|---|---|
| NEW | All 39 | ✓ |
| PROCESSING | None | ✓ |
| ENRICHED | All 39 | ✓ |
| IN_REVIEW | All 39 | ✓ |
| ADJUSTED | All 39 | ✓ |
| READY | All 39 | ✓ |
| PAIRED | All 39 | ✓ |
| MANUAL_REVIEW | All 39 | ✓ |
| ERROR | None | ✓ |
| CONFIRMED | None | ✓ |
| SUPERSEDED | None | ✓ |

**Key Rules**:
- PROCESSING and ERROR statuses are mostly read-only (except processing_notes).
- CONFIRMED and SUPERSEDED statuses are completely read-only and have no transitions.
- processing_notes is always editable, even in terminal statuses, for audit trail purposes.
- PUT /records/{id} on an ENRICHED record does not explicitly set status; instead, the auto-transition to ADJUSTED occurs within the PUT handler.
- Non-editable fields sent to the save endpoint are silently dropped (not rejected).

---

### 4.4 Validation Rules for Confirmation

Before a record can transition from READY to CONFIRMED, the following validation must pass:

#### Required Fields (9 mandatory)
All of the following must be non-null and non-empty:
1. `internal_type`
2. `resolved_customer_id`
3. `validated_currency`
4. `customer_code`
5. `debit_credit`
6. `original_amount`
7. `total_amount`
8. `transaction_date`
9. `settlement_date`

#### Conditional Requirements
In addition to the base required fields, the following conditions are evaluated:

**Condition 1: Security Transactions**
- **IF** `internal_type` matches pattern `SEC_.*|BOND_.*`
- **THEN** require: `resolved_asset_id`, `asset_category`

**Condition 2: Non-Cash Transactions**
- **IF** `internal_type` does NOT match pattern `CASH_.*|FEE_.*`
- **THEN** require: `counterparty_short_code`

**Condition 3: Non-EUR Transactions**
- **IF** `validated_currency` is not equal to "EUR"
- **THEN** require: `fx_rate_to_eur`, `fx_rate_source`

**Condition 4: EUR Parallel Reporting**
- **IF** `requires_eur_parallel` equals "yes"
- **THEN** require: `fx_rate_to_eur`, `fx_rate_source`

**Condition 5: Transactions with Fees**
- **IF** `has_fee` equals "yes"
- **THEN** require: `fee_amount`

**Condition 6: Asset Classification**
- **IF** `resolved_asset_id` is not empty
- **THEN** require: `asset_category`

**Validation Behavior**: If any required field (base or conditional) is missing, the POST /records/confirm endpoint returns a 400 Bad Request response with detailed error messages listing each missing field and its condition (if conditional). The transaction is not confirmed, and no status change occurs.

---

## 5. API Endpoints

All endpoints return JSON responses. HTTP status codes follow REST conventions:
- **200**: Success
- **400**: Validation error or invalid request
- **404**: Record not found
- **409**: Optimistic lock conflict (version mismatch)
- **422**: Invalid status transition
- **500**: Internal server error

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

**Purpose**: Paginated listing of records from the configured table. Supports filtering, searching, sorting, and pagination. Used by all 5 datalist views in the enrichment workspace.

**Request**:
```
GET /records?filter=status=ready,source_tp=bank&search=customer_code:CUST001&page=1&pageSize=50&sort=transaction_date&order=desc
```

**Query Parameters**:

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| filter | String | No | — | Comma-separated `field=value` pairs for exact match. Example: `status=ready,source_tp=bank` |
| search | String | No | — | Substring search as `field:value`. Example: `customer_code:CUST001` (generates `LIKE %CUST001%`) |
| page | Integer | No | 1 | 1-based page number |
| pageSize | Integer | No | 20 | Records per page (max: configured maxPageSize, clamped to 200) |
| sort | String | No | configured defaultSort | Any form element ID, or shortcuts: `created` (→ dateCreated), `modified` (→ dateModified) |
| order | String | No | asc | `asc` or `desc` |

**HQL Generation**: The plugin builds HQL conditions dynamically from the filter and search parameters. All field references use form element IDs (no `c_` prefix). When no filters are present, an empty string `""` is passed to FormDataDao (never null).

Example generated HQL:
```
WHERE e.customProperties.status = ? AND e.customProperties.source_tp = ? AND e.customProperties.customer_code LIKE ?
```

**Numeric Sort Handling**: Fields like `original_amount`, `fee_amount`, `total_amount` are stored as VARCHAR in Joget. For correct numeric sorting, the plugin fetches all matching rows, sorts them numerically in Java, then returns the paginated slice.

**Response (200 OK)**:
```json
{
  "records": [
    {
      "id": "ENR-042",
      "dateCreated": 1737936000000,
      "dateModified": 1737936000000,
      "createdBy": "admin",
      "modifiedBy": "analyst_01",
      "version": 3,
      "source_tp": "bank",
      "origin": "pipeline",
      "transaction_date": "2026-01-15",
      "settlement_date": "2026-01-17",
      "statement_id": "STMT-2026-01",
      "internal_type": "TRANSFER_OUT",
      "description": "Wire transfer to supplier",
      "original_amount": "50000.00",
      "fee_amount": "125.00",
      "total_amount": "50125.00",
      "validated_currency": "EUR",
      "fx_rate_to_eur": "1.0000",
      "fx_rate_date": "2026-01-15",
      "fx_rate_source": "ECB",
      "requires_eur_parallel": "no",
      "resolved_customer_id": "CUST-001",
      "customer_code": "MAIN",
      "resolved_asset_id": "",
      "asset_category": "",
      "counterparty_id": "SUPP-456",
      "counterparty_short_code": "SUPP-456",
      "has_fee": "yes",
      "status": "ready",
      "processing_notes": "Verified with procurement",
      "lineage_note": "Original enrichment by batch pipeline",
      "parent_enrichment_id": "",
      "group_id": "",
      "split_sequence": "",
      "type_confidence": "high"
    }
  ],
  "page": 1,
  "pageSize": 50,
  "total": 287,
  "totalPages": 6,
  "sort": "transaction_date",
  "order": "desc",
  "ms": 42
}
```

**Field Descriptions**:
- `records`: Array of enrichment record objects, each containing all custom + standard properties.
- `page`: Current 1-based page number.
- `pageSize`: Records returned on this page.
- `total`: Total records matching the filter/search criteria.
- `totalPages`: Total pages available.
- `sort`: Sorting field (echoed from request).
- `order`: Sorting direction (echoed from request).
- `ms`: Response time in milliseconds.

**Error Responses**:
- **400 Bad Request**: Invalid filter or search field name (not matching `^[a-zA-Z_][a-zA-Z0-9_]*$`).
  ```json
  {
    "error": "INVALID_PARAMS",
    "message": "Invalid filter field name: $invalid",
    "ms": 5
  }
  ```

- **500 Internal Server Error**: Table not found or database error.
  ```json
  {
    "error": "DATABASE_ERROR",
    "message": "Error fetching records from table: trx_enrichment",
    "ms": 50
  }
  ```

---

### 5.3 GET /records/{id}

**Purpose**: Retrieve a single enrichment record by primary key.

**Request**:
```
GET /records/ENR-042
```

**Path Parameters**:

| Parameter | Type | Description |
|---|---|---|
| id | String | Record primary key |

**Response (200 OK)**:
```json
{
  "id": "ENR-042",
  "dateCreated": 1737936000000,
  "dateModified": 1737936000000,
  "createdBy": "admin",
  "modifiedBy": "analyst_01",
  "version": 3,
  "source_tp": "bank",
  "origin": "pipeline",
  "transaction_date": "2026-01-15",
  "settlement_date": "2026-01-17",
  "statement_id": "STMT-2026-01",
  "internal_type": "TRANSFER_OUT",
  "description": "Wire transfer to supplier",
  "original_amount": "50000.00",
  "fee_amount": "125.00",
  "total_amount": "50125.00",
  "validated_currency": "EUR",
  "fx_rate_to_eur": "1.0000",
  "fx_rate_date": "2026-01-15",
  "fx_rate_source": "ECB",
  "requires_eur_parallel": "no",
  "resolved_customer_id": "CUST-001",
  "customer_code": "MAIN",
  "resolved_asset_id": "",
  "asset_category": "",
  "counterparty_id": "SUPP-456",
  "counterparty_short_code": "SUPP-456",
  "has_fee": "yes",
  "status": "ready",
  "processing_notes": "Verified with procurement",
  "lineage_note": "Original enrichment by batch pipeline",
  "parent_enrichment_id": "",
  "group_id": "",
  "split_sequence": "",
  "type_confidence": "high",
  "ms": 12
}
```

**Error Responses**:
- **404 Not Found**: Record does not exist.
  ```json
  {
    "error": "NOT_FOUND",
    "message": "Record not found: ENR-999",
    "ms": 8
  }
  ```

- **500 Internal Server Error**: Database error.
  ```json
  {
    "error": "DATABASE_ERROR",
    "message": "Error loading record: ENR-042",
    "ms": 15
  }
  ```

---

### 5.4 PUT /records/{id}

**Purpose**: Update field values on a single record with optimistic locking and optional auto-status transition.

**Request**:
```
PUT /records/ENR-042
Content-Type: application/json

{
  "version": 3,
  "internal_type": "TRANSFER_OUT",
  "resolved_customer_id": "CUST-002",
  "counterparty_short_code": "SUPP-789",
  "processing_notes": "Updated after customer clarification"
}
```

**Request Body**:

| Field | Type | Required | Description |
|---|---|---|---|
| version | Integer | **Yes** | Current version number for optimistic locking. If version mismatch, returns 409. |
| (field IDs) | String | No | Any form element IDs from the record to update. Only provided fields are changed. |

**Response (200 OK)**:
```json
{
  "id": "ENR-042",
  "dateCreated": 1737936000000,
  "dateModified": 1737936100000,
  "createdBy": "admin",
  "modifiedBy": "analyst_01",
  "version": 4,
  "source_tp": "bank",
  "origin": "pipeline",
  "transaction_date": "2026-01-15",
  "settlement_date": "2026-01-17",
  "statement_id": "STMT-2026-01",
  "internal_type": "TRANSFER_OUT",
  "description": "Wire transfer to supplier",
  "original_amount": "50000.00",
  "fee_amount": "125.00",
  "total_amount": "50125.00",
  "validated_currency": "EUR",
  "fx_rate_to_eur": "1.0000",
  "fx_rate_date": "2026-01-15",
  "fx_rate_source": "ECB",
  "requires_eur_parallel": "no",
  "resolved_customer_id": "CUST-002",
  "customer_code": "MAIN",
  "resolved_asset_id": "",
  "asset_category": "",
  "counterparty_id": "SUPP-456",
  "counterparty_short_code": "SUPP-789",
  "has_fee": "yes",
  "status": "adjusted",
  "processing_notes": "Updated after customer clarification",
  "lineage_note": "Original enrichment by batch pipeline",
  "parent_enrichment_id": "",
  "group_id": "",
  "split_sequence": "",
  "type_confidence": "high",
  "ms": 28
}
```

**Behavior**:
1. **Version Check**: If the supplied `version` does not match the current version, returns 409 Conflict (see below).
2. **Field Validation**: Only the provided fields are updated. Standard metadata (id, dateCreated, createdBy) are never modified.
3. **Auto-Status Transition**: If the record is in ENRICHED status **before** the update, and at least one field is actually modified, the plugin automatically transitions the status to ADJUSTED. This transition is performed via `StatusManager.transition()` and is audit-logged.
4. **Confidence Overrides**: After fields are applied, the plugin evaluates `confidenceOverrides` rules from the validationConfig. If a `triggerField` matches any changed field, the plugin sets or clears fields as specified.
5. **Version Increment**: The version is incremented by 1 and saved with the record.

**Error Responses**:

- **400 Bad Request**: Missing version field.
  ```json
  {
    "error": "VALIDATION_ERROR",
    "message": "Missing required field: version",
    "ms": 5
  }
  ```

- **400 Bad Request**: Record in terminal status (CONFIRMED or SUPERSEDED).
  ```json
  {
    "error": "TERMINAL_STATUS",
    "message": "Cannot update record ENR-042 in terminal status: confirmed",
    "ms": 8
  }
  ```

- **404 Not Found**: Record does not exist.
  ```json
  {
    "error": "NOT_FOUND",
    "message": "Record not found: ENR-999",
    "ms": 8
  }
  ```

- **409 Conflict**: Version mismatch (optimistic lock failure).
  ```json
  {
    "error": "VERSION_CONFLICT",
    "message": "Version mismatch: expected 3, got 4",
    "currentVersion": 4,
    "ms": 10
  }
  ```

- **500 Internal Server Error**: Database error.
  ```json
  {
    "error": "DATABASE_ERROR",
    "message": "Error updating record: ENR-042",
    "ms": 15
  }
  ```

---

### 5.5 POST /records/{id}/status

**Purpose**: Transition a single record's status via StatusManager. Validates the transition against the state machine, logs to audit_log, and returns the result.

**Request**:
```
POST /records/ENR-042/status
Content-Type: application/json

{
  "targetStatus": "ready",
  "reason": "Approved by analyst after review"
}
```

**Request Body**:

| Field | Type | Required | Description |
|---|---|---|---|
| targetStatus | String | **Yes** | Status code (lowercase, e.g., "ready", "enriched", "confirmed") |
| reason | String | No | Optional reason for the transition (for audit trail). Defaults to "Status transition via API". |

**Response (200 OK)**:
```json
{
  "id": "ENR-042",
  "previousStatus": "adjusted",
  "newStatus": "ready",
  "modifiedBy": "analyst_01",
  "ms": 35
}
```

**Behavior**:
1. **Status Enum Lookup**: Converts the `targetStatus` code to a `Status` enum. If invalid, returns 400.
2. **Transition Validation**: Calls `StatusManager.transition()` which validates the transition against the authoritative state machine. If invalid, throws `InvalidTransitionException`.
3. **Audit Logging**: StatusManager automatically writes an audit entry to `audit_log` (entity_type, entity_id, from_status, to_status, triggered_by="enrichment-api", reason, timestamp).
4. **Return Current State**: Reloads the record and returns its updated status and metadata.

**Error Responses**:

- **400 Bad Request**: Missing targetStatus or unknown status code.
  ```json
  {
    "error": "VALIDATION_ERROR",
    "message": "Unknown status code: invalid_status",
    "ms": 5
  }
  ```

- **404 Not Found**: Record does not exist.
  ```json
  {
    "error": "NOT_FOUND",
    "message": "Record not found: ENR-999",
    "ms": 8
  }
  ```

- **422 Unprocessable Entity**: Invalid status transition (current status does not allow transition to target status).
  ```json
  {
    "error": "INVALID_TRANSITION",
    "message": "Cannot transition from 'confirmed' to 'enriched': transition not allowed",
    "currentStatus": "confirmed",
    "targetStatus": "enriched",
    "validTransitions": [],
    "ms": 12
  }
  ```

- **500 Internal Server Error**: Database or framework error.
  ```json
  {
    "error": "DATABASE_ERROR",
    "message": "Error transitioning status for record: ENR-042",
    "ms": 15
  }
  ```

---

### 5.6 POST /records/status

**Purpose**: Transition multiple records' statuses in a batch. Failures are collected and reported; successes are committed.

**Request**:
```
POST /records/status
Content-Type: application/json

{
  "recordIds": ["ENR-042", "ENR-043", "ENR-044"],
  "targetStatus": "ready",
  "reason": "Batch approval by supervisor"
}
```

**Request Body**:

| Field | Type | Required | Description |
|---|---|---|---|
| recordIds | Array of Strings | **Yes** | List of record IDs to transition |
| targetStatus | String | **Yes** | Status code (lowercase) |
| reason | String | No | Optional reason for audit trail |

**Response (200 OK)**:
```json
{
  "succeeded": [
    {
      "id": "ENR-042",
      "previousStatus": "adjusted",
      "newStatus": "ready",
      "modifiedBy": "supervisor"
    },
    {
      "id": "ENR-043",
      "previousStatus": "adjusted",
      "newStatus": "ready",
      "modifiedBy": "supervisor"
    }
  ],
  "failed": [
    {
      "id": "ENR-044",
      "currentStatus": "confirmed",
      "error": "Cannot transition from 'confirmed' to 'ready': transition not allowed"
    }
  ],
  "ms": 156
}
```

**Behavior**:
1. **Per-Record Transition**: For each record ID, calls `transitionStatus()` individually.
2. **Failure Collection**: If a record cannot be transitioned (not found, invalid transition, etc.), the error is collected and returned in the `failed` array. Other records continue to be processed.
3. **No Rollback**: Unlike JDBC batch operations, individual record transitions are independent. If ENR-042 succeeds but ENR-043 fails, ENR-042 remains in its new status.

**Error Responses**:

- **400 Bad Request**: Missing recordIds or targetStatus.
  ```json
  {
    "error": "VALIDATION_ERROR",
    "message": "Missing required field: recordIds",
    "ms": 5
  }
  ```

- **500 Internal Server Error**: Database error.
  ```json
  {
    "error": "DATABASE_ERROR",
    "message": "Batch transition failed",
    "ms": 50
  }
  ```

---

### 5.7 DELETE /records/{id}

**Purpose**: Delete a record. Only allowed for statuses: NEW, ERROR, MANUAL_REVIEW.

**Request**:
```
DELETE /records/ENR-042
```

**Path Parameters**:

| Parameter | Type | Description |
|---|---|---|
| id | String | Record primary key |

**Response (200 OK)**:
```json
{
  "id": "ENR-042",
  "message": "Record deleted successfully",
  "ms": 18
}
```

**Behavior**:
1. **Status Check**: Loads the record and verifies its status is in DELETABLE_STATUSES: NEW, ERROR, or MANUAL_REVIEW.
2. **Delete**: Removes the record from the database via `FormDataDao.delete()`.
3. **No Audit**: Deletion does not generate an audit log entry (this may be added in a future version).

**Error Responses**:

- **404 Not Found**: Record does not exist.
  ```json
  {
    "error": "NOT_FOUND",
    "message": "Record not found: ENR-999",
    "ms": 8
  }
  ```

- **400 Bad Request**: Record status does not allow deletion.
  ```json
  {
    "error": "DELETE_NOT_ALLOWED",
    "message": "Cannot delete record ENR-042 in status 'ready'. Only records with status new, error, or manual_review can be deleted.",
    "currentStatus": "ready",
    "ms": 10
  }
  ```

- **500 Internal Server Error**: Database error.
  ```json
  {
    "error": "DATABASE_ERROR",
    "message": "Error deleting record: ENR-042",
    "ms": 15
  }
  ```

---

### 5.8 GET /summary

**Purpose**: Returns per-statement summary counts by status using database GROUP BY aggregation.

**Request**:
```
GET /summary
```

**Query Parameters**: None

**Response (200 OK)**:
```json
{
  "statements": [
    {
      "statementId": "STMT-2026-01",
      "total": 285,
      "new": 12,
      "working": 187,
      "ready": 74,
      "confirmed": 12,
      "error": 0
    },
    {
      "statementId": "STMT-2026-02",
      "total": 156,
      "new": 3,
      "working": 98,
      "ready": 51,
      "confirmed": 4,
      "error": 0
    }
  ],
  "ms": 125
}
```

**Field Descriptions**:
- `statementId`: Statement ID (from the configured `statementField`).
- `total`: Count of non-superseded records.
- `new`: Count of records with status=new.
- `working`: Count of records in active statuses: enriched, adjusted, in_review, paired, manual_review.
- `ready`: Count of records with status=ready.
- `confirmed`: Count of records with status=confirmed.
- `error`: Count of records with status=error.

**SQL Query** (behind the scenes):
```sql
SELECT c_statement_id AS stmt_id,
  COUNT(CASE WHEN c_status != 'superseded' THEN 1 END) AS total,
  COUNT(CASE WHEN c_status = 'new' THEN 1 END) AS cnt_new,
  COUNT(CASE WHEN c_status IN ('enriched','adjusted','in_review','paired','manual_review') THEN 1 END) AS working,
  COUNT(CASE WHEN c_status = 'ready' THEN 1 END) AS ready,
  COUNT(CASE WHEN c_status = 'confirmed' THEN 1 END) AS confirmed,
  COUNT(CASE WHEN c_status = 'error' THEN 1 END) AS error
FROM app_fd_trx_enrichment
WHERE c_status != 'superseded'
GROUP BY c_statement_id
ORDER BY c_statement_id
```

**Error Responses**:

- **500 Internal Server Error**: Database error or reconciliation config not set.
  ```json
  {
    "error": "DATABASE_ERROR",
    "message": "Error computing summary",
    "ms": 50
  }
  ```

---

### 5.9 GET /reconciliation/{statementId}

**Purpose**: Computes multi-currency reconciliation for a statement. Compares source totals (F01.03, F01.04) + manual adjustments vs. confirmed output and remaining in-flight records.

**Request**:
```
GET /reconciliation/STMT-2026-01
```

**Path Parameters**:

| Parameter | Type | Description |
|---|---|---|
| statementId | String | Statement ID to reconcile |

**Response (200 OK)**:
```json
{
  "statementId": "STMT-2026-01",
  "isFinalConfirmation": false,
  "currencies": [
    {
      "currency": "EUR",
      "sourceInput": 1250000.00,
      "manualAdj": 5000.00,
      "adjustedInput": 1255000.00,
      "output": 1200000.00,
      "remaining": 55000.00,
      "discrepancy": 0.00,
      "tolerance": 0.02,
      "withinTolerance": true
    },
    {
      "currency": "USD",
      "sourceInput": 500000.00,
      "manualAdj": 0.00,
      "adjustedInput": 500000.00,
      "output": 450000.00,
      "remaining": 50000.00,
      "discrepancy": 0.00,
      "tolerance": 0.02,
      "withinTolerance": true
    }
  ],
  "ms": 245
}
```

**Reconciliation Logic**:

The reconciliation computation follows this algorithm:

1. **Source Input**: Sum all amounts from source tables (F01.03, F01.04) for the given statement and currency.
2. **Manual Adjustments**: Sum all F01.05 records with `origin = "manual"` (configurable `manualOriginValue`) that are not superseded.
3. **Adjusted Input**: Source Input + Manual Adjustments.
4. **Output**: Sum all F01.05 records with `status = "confirmed"`.
5. **Remaining**: Sum all F01.05 records with `status NOT IN ("confirmed", "superseded")`.
6. **Discrepancy**: Adjusted Input - Output - Remaining.
7. **Within Tolerance**: `|Discrepancy| <= tolerance[currency]` (if currency not found, uses `tolerance._default`).
8. **isFinalConfirmation**: True if `remaining = 0` for all currencies (all records have been confirmed or superseded).

**Field Descriptions**:
- `statementId`: Input statement ID.
- `isFinalConfirmation`: True if the statement has no remaining active records (all are confirmed or superseded). Useful for determining if reconciliation is "final" or provisional.
- `currencies`: Array of per-currency reconciliation summaries.
  - `currency`: Three-letter currency code (e.g., EUR, USD).
  - `sourceInput`: Sum from source tables.
  - `manualAdj`: Manual adjustments added to enrichment.
  - `adjustedInput`: Source + manual adjustments.
  - `output`: Confirmed records (ready to post).
  - `remaining`: Active non-confirmed records (still in enrichment workflow).
  - `discrepancy`: Gap between adjusted input and actual output+remaining.
  - `tolerance`: Allowed variance (in currency units, e.g., 0.02 = ±0.02).
  - `withinTolerance`: Boolean; true if discrepancy is within tolerance.

**Error Responses**:

- **400 Bad Request**: Reconciliation config is not set in validationConfig.
  ```json
  {
    "error": "CONFIG_ERROR",
    "message": "Reconciliation config is not set",
    "ms": 5
  }
  ```

- **500 Internal Server Error**: Database error.
  ```json
  {
    "error": "DATABASE_ERROR",
    "message": "Error computing reconciliation",
    "ms": 50
  }
  ```

---

### 5.10 POST /records/confirm

**Purpose**: Confirms multiple READY records, validating required fields and transitioning to CONFIRMED status. Atomic operation using JDBC transactions.

**Request**:
```
POST /records/confirm
Content-Type: application/json

{
  "recordIds": ["ENR-042", "ENR-043"],
  "allowPartial": false
}
```

**Request Body**:

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| recordIds | Array of Strings | **Yes** | — | List of record IDs to confirm (must all be in READY status) |
| allowPartial | Boolean | No | false | If false, fails entire batch on any validation error. If true, skips invalid records and confirms valid ones. |

**Response (200 OK)**:
```json
{
  "confirmed": 2,
  "confirmedRecords": [
    {
      "id": "ENR-042",
      "previousStatus": "ready",
      "newStatus": "confirmed"
    },
    {
      "id": "ENR-043",
      "previousStatus": "ready",
      "newStatus": "confirmed"
    }
  ],
  "skipped": [],
  "validationErrors": [],
  "reconciliation": {
    "statementId": "STMT-2026-01",
    "isFinalConfirmation": false,
    "currencies": [
      {
        "currency": "EUR",
        "sourceInput": 1250000.00,
        "manualAdj": 5000.00,
        "adjustedInput": 1255000.00,
        "output": 1200000.00,
        "remaining": 55000.00,
        "discrepancy": 0.00,
        "tolerance": 0.02,
        "withinTolerance": true
      }
    ]
  },
  "ms": 450
}
```

**Confirmation Algorithm**:

1. **Filter by Status**: Filter input list to only records with `status = "ready"`. Others are added to `skipped` list with reason "status is 'X', not 'ready'".
2. **Validate Required Fields**: For each remaining record, call `validateRecord()` which checks all required fields (base + conditional). If validation fails:
   - If `allowPartial = false`: Return immediately with 0 confirmed, errors, and no state changes.
   - If `allowPartial = true`: Add to `validationErrors` and continue to next record.
3. **Reconciliation Computation** (optional): If reconciliation is configured, compute per-statement reconciliation after validation passes. This is informational and does not block confirmation (can proceed even if discrepancy exceeds tolerance).
4. **JDBC Transaction**: Begin transaction, then for each valid record:
   - Update `status = "confirmed"`, `confirmed_by = "enrichment-api"`, `confirmed_at = ISO timestamp`.
   - Insert audit log entry.
5. **Commit**: If all updates succeed, commit. If any update fails, rollback entire batch.

**Confirmation Fields** (set automatically):
- `status`: Set to "confirmed"
- `confirmed_by`: Set to "enrichment-api" (from confirmation config)
- `confirmed_at`: Set to ISO 8601 timestamp (from confirmation config)

**Response Fields**:
- `confirmed`: Count of records successfully confirmed.
- `confirmedRecords`: Array of confirmed record objects (id, previousStatus, newStatus).
- `skipped`: Array of records not in READY status (with reason).
- `validationErrors`: Array of validation failures (if `allowPartial = true` or on errors).
- `reconciliation`: Per-statement reconciliation summary (if configured and computed).

**Error Responses**:

- **400 Bad Request**: Missing recordIds or validation failure with `allowPartial = false`.
  ```json
  {
    "error": "VALIDATION_FAILED",
    "message": "Confirmation failed: validation errors",
    "confirmed": 0,
    "skipped": [],
    "validationErrors": [
      {
        "id": "ENR-042",
        "errors": [
          "Missing internal_type",
          "Missing resolved_customer_id"
        ]
      }
    ],
    "ms": 50
  }
  ```

- **500 Internal Server Error**: Database transaction error.
  ```json
  {
    "error": "DATABASE_ERROR",
    "message": "Confirmation failed: transaction error",
    "ms": 150
  }
  ```

---

### 5.11 POST /records/{id}/split

**Purpose**: Splits a single record into multiple child records with allocated amounts and customer codes. Original becomes SUPERSEDED. Atomic JDBC transaction.

**Request**:
```
POST /records/ENR-042/split
Content-Type: application/json

{
  "allocations": [
    {
      "customer_code": "CUST-001",
      "original_amount": "25000.00",
      "fee_amount": "62.50"
    },
    {
      "customer_code": "CUST-002",
      "original_amount": "25000.00",
      "fee_amount": "62.50"
    }
  ]
}
```

**Request Body**:

| Field | Type | Required | Description |
|---|---|---|---|
| allocations | Array of Objects | **Yes** | Array of 2+ allocation objects, each with customer_code, original_amount, and fee_amount |

**Allocation Object**:

| Field | Type | Required | Description |
|---|---|---|---|
| customer_code | String | **Yes** | Customer code for the child record |
| original_amount | String | **Yes** | Amount (decimal, e.g., "25000.00") |
| fee_amount | String | No | Fee amount. If not provided, calculated from parent proportionally. |
| *(any editable field)* | String | No | Per-child field override — see below |

**Per-Child Field Overrides** (added in WS-3):

In addition to the three standard allocation fields (customer_code, original_amount, fee_amount), each allocation object may include **any field from EDITABLE_FIELDS** (§4.3). These override the parent's value for that child only. Non-editable field keys are silently ignored.

Common per-child overrides:
- `internal_type` — loan payment split creates children with different types (e.g., LOAN_PAYMENT, INT_INCOME, COMM_FEE)
- `description` — per-child descriptions for accounting clarity
- `transaction_date` — multi-period accrual split puts children in different periods
- `loan_id`, `loan_direction` — loan-linked children
- `gl_debit_override`, `gl_credit_override` — fee disaggregation with per-child GL targets

Example with per-child overrides:
```json
{
  "split": true,
  "id": "ENR-042",
  "allocations": [
    {
      "customer_code": "CUST-001",
      "original_amount": "25000.00",
      "fee_amount": "62.50",
      "internal_type": "LOAN_PAYMENT",
      "loan_id": "LOAN-001",
      "description": "Principal portion"
    },
    {
      "customer_code": "CUST-001",
      "original_amount": "5000.00",
      "fee_amount": "0",
      "internal_type": "INT_INCOME",
      "loan_id": "LOAN-001",
      "description": "Interest portion"
    }
  ]
}
```

**Response (200 OK)**:
```json
{
  "parentId": "ENR-042",
  "parentStatus": "superseded",
  "children": [
    {
      "id": "ENR-042-S1",
      "customer_code": "CUST-001",
      "original_amount": 25000.00,
      "split_sequence": 1
    },
    {
      "id": "ENR-042-S2",
      "customer_code": "CUST-002",
      "original_amount": 25000.00,
      "split_sequence": 2
    }
  ],
  "ms": 180
}
```

**Split Algorithm**:

1. **Load Parent**: Load the parent record via JDBC.
2. **Status Check**: Verify parent status is in SPLITTABLE_STATUSES: ENRICHED, ADJUSTED, IN_REVIEW, or READY. Reject if not.
3. **Validate Allocations**:
   - Minimum 2 allocations required.
   - All allocations must have non-empty customer_code.
   - Sum of allocation amounts must equal parent original_amount (within 0.01 tolerance).
4. **Fee Allocation**: Sum allocation fees. If remainder exists (due to rounding), add to the last allocation's fee.
5. **Child ID Generation**: For each allocation, child ID = `{parentId}-S{sequence}` (e.g., ENR-042-S1, ENR-042-S2).
6. **Copy Parent Fields**: Copy all parent custom fields to each child, except:
   - Standard metadata (id, dateCreated, dateModified, createdBy, modifiedBy, version)
7. **Override Child Fields**:
   - `original_amount`, `fee_amount`, `total_amount`: From allocation
   - `base_amount_eur`: Calculate from total × fx_rate
   - `customer_code`: From allocation
   - `origin`: Set to "split"
   - `parent_enrichment_id`: Set to parent ID
   - `group_id`: Set to a UUID (same for all children in this split)
   - `split_sequence`: Set to 1, 2, 3, ... N
   - `lineage_note`: Set to "Split from {parentId}: allocation to {customer_code}"
   - `status`: Set to ENRICHED (children start as enriched)
   - `version`: Reset to 0
8. **Parent Supersession**:
   - Update parent `status` to SUPERSEDED
   - Insert audit entry for parent
9. **Child Inserts**: Insert all child records in a single JDBC transaction. If any insert fails, rollback entire operation.

**Child Status**: All child records start in ENRICHED status, allowing them to be further modified, reviewed, split again, or merged.

**Fee Distribution**: By default, fees are distributed proportionally or assigned to the first child, depending on split configuration (configurable in splitMerge section of validationConfig). The algorithm auto-adjusts the last child's fee to account for rounding.

**Error Responses**:

- **400 Bad Request**: Invalid allocations (fewer than 2, amount mismatch, missing customer_code).
  ```json
  {
    "error": "VALIDATION_FAILED",
    "message": "Amounts do not sum to source. Parent: 50000.00, allocations: 49999.99, remaining: 0.01",
    "ms": 25
  }
  ```

- **404 Not Found**: Parent record not found.
  ```json
  {
    "error": "NOT_FOUND",
    "message": "Record not found: ENR-999",
    "ms": 8
  }
  ```

- **400 Bad Request**: Parent status does not allow split.
  ```json
  {
    "error": "INVALID_STATUS",
    "message": "Record status 'confirmed' does not allow split. Allowed: enriched, adjusted, in_review, ready",
    "ms": 10
  }
  ```

- **500 Internal Server Error**: Database transaction error.
  ```json
  {
    "error": "DATABASE_ERROR",
    "message": "Split failed: transaction error",
    "ms": 100
  }
  ```

---

### 5.12 POST /records/merge

**Purpose**: Merges multiple source records into a single combined record. All sources become SUPERSEDED. Atomic JDBC transaction.

**Request**:
```
POST /records/merge
Content-Type: application/json

{
  "sourceIds": ["ENR-001", "ENR-002", "ENR-003"],
  "mergedFields": {
    "internal_type": "CONSOLIDATED",
    "counterparty_short_code": "CPARTY-999",
    "customer_code": "CUST-001"
  }
}
```

**Request Body**:

| Field | Type | Required | Description |
|---|---|---|---|
| sourceIds | Array of Strings | **Yes** | List of 2+ source record IDs to merge |
| mergedFields | Object | No | Field overrides for the merged record (applies after field unanimity check) |

**Response (200 OK)**:
```json
{
  "mergedId": "550e8400-e29b-41d4-a716-446655440000",
  "sourceIds": ["ENR-001", "ENR-002", "ENR-003"],
  "original_amount": 150000.00,
  "fee_amount": 350.00,
  "total_amount": 150350.00,
  "ms": 220
}
```

**Merge Algorithm**:

1. **Load Sources**: Load all source records via JDBC.
2. **Status Check**: Verify all sources are in MERGEABLE_STATUSES: ENRICHED, ADJUSTED, or IN_REVIEW. Reject if any source is in a non-mergeable status (e.g., READY, CONFIRMED, ERROR).
3. **Validation**:
   - All sources must have the same `statement_id`.
   - All sources must have the same `validated_currency`.
   - Minimum 2 sources required.
4. **Amount Aggregation**:
   - `original_amount`: Sum of all sources' original_amount.
   - `fee_amount`: Sum of all sources' fee_amount.
   - `total_amount`: Sum of all sources' total_amount.
   - `base_amount_eur`: Sum of all sources' base_amount_eur.
5. **Field Resolution**:
   - For each non-amount field, check if all sources agree (unanimous).
   - If all sources have the same value, use that value for the merged record.
   - If sources disagree, set the field to empty ("") and note it as needing manual resolution.
6. **Apply Overrides**: Apply `mergedFields` to set or correct fields. Amount fields cannot be overridden (computed from sources).
7. **Required Field Check**:
   - `internal_type`, `customer_code`, `debit_credit` must be non-empty in the merged record.
   - If any is missing, return 400 Bad Request with error listing missing required fields.
8. **Merged ID Generation**: Generate a new UUID for the merged record.
9. **Lineage Fields**:
   - `origin`: Set to "merge"
   - `group_id`: UUID (shared for all records in the merge operation)
   - `lineage_note`: "Merged from: {source1}, {source2}, {source3}"
   - `status`: Set to ENRICHED (merged record starts as enriched)
   - `version`: Reset to 0
10. **Insert Merged Record**: Insert the merged record via JDBC.
11. **Supersede Sources**: For each source, update `status = SUPERSEDED`, `group_id = {merged group_id}`, and insert audit entry.
12. **Commit**: If all operations succeed, commit. If any fails, rollback entire merge.

**Merge Constraints**:
- **NOT mergeable**: READY, CONFIRMED, SUPERSEDED, PAIRED, MANUAL_REVIEW, NEW, PROCESSING, ERROR
- **Mergeable**: ENRICHED, ADJUSTED, IN_REVIEW only
- **Same statement requirement**: All sources must be from the same statement (prevents cross-statement consolidation).
- **Same currency requirement**: All sources must have the same validated_currency (prevents currency mixing).

**Merged Record Status**: The merged record starts in ENRICHED status, allowing it to proceed through the normal enrichment workflow.

**Error Responses**:

- **400 Bad Request**: Fewer than 2 sources or validation failure.
  ```json
  {
    "error": "VALIDATION_FAILED",
    "message": "Merge requires at least 2 records",
    "ms": 5
  }
  ```

- **400 Bad Request**: Sources from different statements or currencies.
  ```json
  {
    "error": "VALIDATION_FAILED",
    "message": "Cannot merge records from different statements",
    "ms": 15
  }
  ```

- **400 Bad Request**: Source status does not allow merge.
  ```json
  {
    "error": "VALIDATION_FAILED",
    "message": "Record ENR-099 has status 'ready' — only enriched, adjusted, or in_review may be merged",
    "ms": 20
  }
  ```

- **400 Bad Request**: Merged record missing required fields.
  ```json
  {
    "error": "VALIDATION_FAILED",
    "message": "Merge requires these fields to be set (provide in mergedFields if sources differ): internal_type, customer_code",
    "ms": 25
  }
  ```

- **404 Not Found**: One of the source records not found.
  ```json
  {
    "error": "NOT_FOUND",
    "message": "Record not found: ENR-999",
    "ms": 8
  }
  ```

- **500 Internal Server Error**: Database transaction error.
  ```json
  {
    "error": "DATABASE_ERROR",
    "message": "Merge failed: transaction error",
    "ms": 150
  }
  ```

---

### 5.13 Create Record (via dispatch)

**Purpose**: Creates a new F01.05 enrichment record. Used by the workspace for manual entries: interest accruals (§8.1), accrual reversals (§8.2), FX gain/loss entries (§7.3), and ad-hoc manual transactions.

**Dispatch**: Triggered via `GET /records?save=<json>` when the JSON contains `{create:true, fields:{...}}`.

**Request**:
```
GET /records?save={"create":true,"fields":{"internal_type":"INT_INCOME","debit_credit":"C","total_amount":"1500.00","validated_currency":"EUR","transaction_date":"2026-03-31","statement_id":"STMT-2026-03","description":"Interest accrual LOAN-001 March 2026","loan_id":"LOAN-001","original_amount":"1500.00"}}
```

**Request Fields**:

| Field | Type | Required | Description |
|---|---|---|---|
| create | boolean | **Yes** | Must be `true` — triggers create dispatch |
| fields | Object | **Yes** | Field values for the new record |

**Mandatory Fields** (within `fields`):

| Field | Description |
|---|---|
| internal_type | Transaction type code |
| debit_credit | "D" or "C" |
| total_amount | Total amount (decimal string) |
| validated_currency | Currency code (e.g., "EUR") |
| transaction_date | Date (YYYY-MM-DD) |
| statement_id | Statement identifier |

**Behavior**:

1. **Validate mandatory fields**: All 6 must be present and non-empty. Returns 422 if any are missing.
2. **Strip protected fields**: Caller cannot set `id`, `status`, `version`, `origin`, `parent_enrichment_id`, `group_id`, `split_sequence`, `confirmed_by`, `confirmed_at`.
3. **Filter through EDITABLE_FIELDS**: Only fields in the §4.3 editability set are accepted. Others are silently dropped.
4. **Set system fields**: `source_tp` = "manual" (enforced, cannot be overridden), `status` = "enriched", `version` = 0.
5. **Generate ID**: UUID.
6. **Insert**: Via `JdbcHelper.insertRow()`. Sets `createdBy` and `modifiedBy` to current user.
7. **Audit**: Inserts audit entry (null → enriched).

**Response (201 Created)**:
```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "enriched",
  "version": 0,
  "internal_type": "INT_INCOME",
  "debit_credit": "C",
  "total_amount": "1500.00",
  "validated_currency": "EUR",
  "transaction_date": "2026-03-31",
  "source_tp": "manual",
  "ms": 45
}
```

**Error Responses**:

- **422 Unprocessable Entity**: Missing mandatory fields.
  ```json
  {
    "error": "VALIDATION_FAILED",
    "message": "Missing required fields for create: transaction_date, statement_id",
    "ms": 5
  }
  ```

- **400 Bad Request**: Empty or missing `fields` object.
  ```json
  {
    "error": "VALIDATION_ERROR",
    "message": "Missing or empty 'fields' object",
    "ms": 3
  }
  ```

- **500 Internal Server Error**: Database error.

---

## 6. Error Handling

All error responses follow a consistent structure:

```json
{
  "error": "ERROR_CODE",
  "message": "Human-readable error message",
  "ms": 25
}
```

**Common HTTP Status Codes**:

| Status | Meaning | Examples |
|---|---|---|
| **200** | Success | Record returned, status transitioned, confirmation completed |
| **201** | Created | New record created via create dispatch |
| **400** | Bad Request | Invalid parameters, validation failure, status not allowed for operation |
| **404** | Not Found | Record does not exist, table not configured |
| **409** | Conflict | Version mismatch (optimistic lock) |
| **422** | Unprocessable Entity | Invalid status transition |
| **500** | Internal Server Error | Database error, plugin not initialized, framework error |

**Common Error Codes**:

- `VALIDATION_ERROR`: Invalid input (missing field, invalid format)
- `NOT_FOUND`: Record or resource not found
- `VERSION_CONFLICT`: Optimistic lock failure (version mismatch)
- `INVALID_TRANSITION`: Status transition not allowed by state machine
- `TERMINAL_STATUS`: Record in terminal status (CONFIRMED, SUPERSEDED)
- `DELETE_NOT_ALLOWED`: Record status does not allow deletion
- `INVALID_STATUS`: Record status does not allow the requested operation
- `DATABASE_ERROR`: Underlying database error
- `CONFIG_ERROR`: Plugin configuration missing or invalid
- `VALIDATION_FAILED`: Field validation error (required fields missing)

---

## 7. Optimistic Locking

Every F01.05 record contains a `version` field (integer, starting at 0). When a client updates a record via PUT /records/{id}:

1. **Client supplies current version**: `{ "version": 3, "internal_type": "..." }`
2. **Server checks version**: Loads record, compares supplied version to actual version.
3. **Version match**: Update proceeds. Version is incremented and saved.
4. **Version mismatch**: Return 409 Conflict. Client must reload record (GET /records/{id}) to get current version and retry.

**Purpose**: Prevents lost updates in concurrent scenarios where two analysts edit the same record simultaneously. The first update succeeds and increments the version. The second update fails with 409, forcing the second analyst to reload and reapply their changes on the updated record.

---

## 8. Cross-References

- **gam-framework-specification.md**: Authoritative state machine definition, StatusManager behavior, audit logging
- **rows-enrichment-spec.md**: F01.05 field definitions, business domain rules, calculation logic
- **enrichment-workspace-specification.md**: UI contract, form layouts, view definitions, action buttons

---

## 9. Deployment & Configuration

### 9.1 Plugin Deployment

1. **Build**: `mvn clean package`
2. **Deploy**: Upload JAR to Joget plugin console → Plugins → Install
3. **Enable**: Navigate to API Builder, create new API instance
4. **Configure**:
   - Select form (trx_enrichment)
   - Set table name (usually matches form ID)
   - Paste validationConfig JSON

### 9.2 Required Dependencies

- **Joget DX 8.1.6+**: API Builder Plugin framework
- **gam-framework**: Shared library (deploy to `{JOGET_HOME}/wflow/lib/`)
- **FormDataDao**: Standard Joget DAO (provided by platform)

### 9.3 Database Setup

The enrichment-api assumes the standard Joget form table structure:

```sql
CREATE TABLE app_fd_trx_enrichment (
  id VARCHAR(255) PRIMARY KEY,
  dateCreated DATETIME,
  dateModified DATETIME,
  createdBy VARCHAR(255),
  modifiedBy VARCHAR(255),
  version INT DEFAULT 0,
  -- Custom fields (c_fieldId format in DB)
  c_statement_id VARCHAR(255),
  c_origin VARCHAR(50),
  c_transaction_date DATE,
  c_settlement_date DATE,
  c_internal_type VARCHAR(50),
  c_original_amount DECIMAL(20,4),
  c_fee_amount DECIMAL(20,4),
  c_total_amount DECIMAL(20,4),
  c_validated_currency VARCHAR(3),
  c_fx_rate_to_eur DECIMAL(20,8),
  c_resolved_customer_id VARCHAR(255),
  c_customer_code VARCHAR(50),
  c_resolved_asset_id VARCHAR(255),
  c_counterparty_short_code VARCHAR(50),
  c_status VARCHAR(50),
  c_processing_notes TEXT,
  c_parent_enrichment_id VARCHAR(255),
  c_group_id VARCHAR(255),
  c_split_sequence INT,
  c_lineage_note TEXT,
  -- Workspace operations fields (WS-2)
  c_matched_rule_id VARCHAR(255),
  c_type_confidence VARCHAR(50),
  c_customer_display_name VARCHAR(255),
  c_customer_match_method VARCHAR(50),
  c_base_amount_eur DECIMAL(20,4),
  c_base_fee_eur DECIMAL(20,4),
  c_pair_id VARCHAR(255),
  c_acc_post_id VARCHAR(255),
  c_loan_id VARCHAR(255),
  c_loan_direction VARCHAR(50),
  c_loan_resolution_method VARCHAR(50),
  c_source_reference VARCHAR(255),
  c_gl_debit_override VARCHAR(255),
  c_gl_credit_override VARCHAR(255),
  c_gl_override_reason TEXT,
  c_fund_allocation_status VARCHAR(50),
  c_period_locked VARCHAR(10),
  c_source_tp VARCHAR(50),
  ...
);
```

The `app_fd_audit_log` table is created by gam-framework and stores all status transitions.

---

## 10. Examples

### Example 1: Complete Enrichment Workflow

1. **Record created** (status=NEW) by upstream import
2. **Analyst fetches records**: `GET /records?filter=status=new`
3. **Analyst opens record**: `GET /records/ENR-042`
4. **Analyst edits fields**: `PUT /records/ENR-042` with version=0
   - Status auto-transitions to ADJUSTED
5. **Analyst marks as ready**: `POST /records/ENR-042/status` with targetStatus="ready"
6. **Supervisor confirms batch**: `POST /records/confirm` with recordIds=["ENR-042"]
   - Validation passes, status = CONFIRMED
   - Record leaves enrichment scope, downstream posting plugin picks it up

### Example 2: Split a Transaction

1. Customer receives one wire transfer from supplier but realizes it's for multiple projects
2. Analyst calls: `POST /records/ENR-042/split`
   - Allocates to PROJ-001: 25,000
   - Allocates to PROJ-002: 25,000
3. Original becomes SUPERSEDED
4. Two new children created: ENR-042-S1 (PROJ-001), ENR-042-S2 (PROJ-002)
5. Each child can be individually enriched and confirmed

### Example 3: Merge and Reconciliation

1. Three related transactions need to be consolidated
2. Analyst calls: `POST /records/merge` with sourceIds=["ENR-001", "ENR-002", "ENR-003"]
3. Merged record created with summed amounts
4. All sources become SUPERSEDED
5. Call: `GET /reconciliation/STMT-2026-01` to verify balanced at statement level

---

## Revision History

| Version | Date | Author | Changes |
|---|---|---|---|
| 1.0.0 | 2024-07-15 | enrichment-api team | Initial specification |
| 2.0.0 | 2024-12-01 | enrichment-api team | Detailed business rules, complete endpoint schemas, field editability matrix |
| 2.1.0 | 2026-03-08 | enrichment-api team | WS-2: Expanded EDITABLE_FIELDS (22→39) for workspace operations. WS-3: Per-child field overrides in split. WS-4: Create record endpoint (§5.13). |

---

**END OF SPECIFICATION**
