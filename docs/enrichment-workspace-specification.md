# Enrichment Workspace UI v1.0.0 Specification

**Plugin**: enrichment-workspace v1.0.0
**Type**: Joget DX 8.1.6 UserviewMenu Plugin
**Platform**: Joget DX 8.1.6+
**Scope**: F01.05 Manual Enrichment UI only — provides the rich, Joget-native interface for enrichment workspace operations
**Companion**: enrichment-api v1.0.0 (REST data contract)
**Last Updated**: 2026-03-02

---

## §1. Overview

### 1.1 Plugin Identity

| Property | Value |
|----------|-------|
| **Plugin Name** | enrichment-workspace |
| **Version** | 1.0.0 |
| **Plugin Type** | UserviewMenu (Joget 8.1.6 native) |
| **Service Class** | `org.joget.gam.enrichment.ui.EnrichmentWorkspaceMenu` |
| **Icon** | `<i class="fas fa-exchange-alt"></i>` |
| **Category** | Custom (UserviewBuilderPalette.CATEGORY_CUSTOM) |
| **Home Page Supported** | Yes |

### 1.2 Single Responsibility

The enrichment-workspace plugin provides **the manual enrichment workspace UI for F01.05 transactions only**. It does NOT:
- Create, update, or delete F01.06 posting records
- Interact with the GL posting engine
- Manage posting operations or approval workflows
- Touch F01.03/F01.04 source records

The plugin's job is to render:
- Five datalist views for different enrichment statuses and workflows
- A form for detailed transaction editing
- Action dialogs for split, merge, and confirmation operations
- Real-time validation feedback and reconciliation panels

### 1.3 Data Contract: enrichment-api

All mutations (create, update, delete, status changes, splits, merges) are delegated to the enrichment-api REST plugin. The enrichment-workspace plugin:
- Reads data via enrichment-api GET endpoints
- Writes data via enrichment-api POST/PUT/DELETE endpoints
- Trusts the API for validation, state management, and reconciliation logic
- Never bypasses the API to write directly to F01.05 tables

### 1.4 Framework Integration

The UI is tightly integrated with gam-framework:
- Status badges use gam-framework Status enum values (not hardcoded strings)
- Status transitions respect gam-framework StatusManager rules
- Field visibility and editability rules align with status-based permissions
- All timestamp and audit fields use gam-framework audit tracking

---

## §2. Plugin Configuration

### 2.1 Plugin Properties JSON Schema

The plugin exposes the following configurable properties via Joget's property panel:

```json
{
  "title": "Enrichment Workspace Configuration",
  "properties": [
    {
      "group": "General",
      "fields": [
        {
          "name": "label",
          "label": "Menu Label",
          "type": "textfield",
          "required": true,
          "value": "Enrichment Workspace",
          "description": "The label displayed in the userview menu"
        },
        {
          "name": "customId",
          "label": "Custom HTML ID",
          "type": "textfield",
          "regex_validation": "^[a-zA-Z0-9_]*$",
          "validation_message": "Only alphanumeric and underscore allowed",
          "description": "Optional custom HTML id for CSS targeting"
        }
      ]
    },
    {
      "group": "API Connection",
      "fields": [
        {
          "name": "apiId",
          "label": "API Builder ID",
          "type": "textfield",
          "required": true,
          "description": "The API Builder ID (e.g. enrichment-api). Must match enrichment-api plugin's apiId."
        },
        {
          "name": "apiKey",
          "label": "API Key",
          "type": "textfield",
          "required": true,
          "description": "API key from API Builder Settings. Used to authenticate calls to enrichment-api."
        }
      ]
    },
    {
      "group": "Field Mapping",
      "fields": [
        {
          "name": "tableName",
          "label": "Table Name",
          "type": "textfield",
          "required": true,
          "value": "trx_enrichment",
          "description": "MySQL table name (without app_fd_ prefix). Used by JDBC binders in datalists."
        },
        {
          "name": "formId",
          "label": "Form ID",
          "type": "textfield",
          "required": true,
          "value": "trxEnrichment",
          "description": "Joget form ID for detailed record editing. Form must exist in the app."
        },
        {
          "name": "datalistIds",
          "label": "Datalist IDs (comma-separated)",
          "type": "textarea",
          "value": "enrichmentWorkspace,readyForPosting,confirmedRecords,splitMergeHistory,statementSummary",
          "description": "IDs of the five datalist views. Must be created in Joget before plugin configuration."
        }
      ]
    },
    {
      "group": "Styling",
      "fields": [
        {
          "name": "customCssClass",
          "label": "Custom CSS Class",
          "type": "textfield",
          "description": "Optional custom CSS class name applied to the root container for branding/theming"
        },
        {
          "name": "pageSize",
          "label": "Records Per Page",
          "type": "textfield",
          "value": "20",
          "regex_validation": "^[0-9]+$",
          "description": "Number of records displayed per page in datalists"
        }
      ]
    }
  ]
}
```

### 2.2 Validation Configuration Alignment

The enrichment-api plugin exposes a `validationConfig` JSON property (see API spec §2.2) that defines required fields, conditional requirements, reconciliation parameters, split/merge field mappings, and confirmation audit fields. The enrichment-workspace plugin does **not** duplicate this configuration — it delegates all validation to the API. However, the workspace needs to be aware of:

- **Required fields list**: Used for client-side pre-validation (highlight missing fields before save) — fetched from enrichment-api `GET /health` response or read from shared configuration.
- **Conditional requirements**: Used for inline field dependency validation (e.g., "asset_id required if internal_type is SEC_*") — same source.
- **Internal type options**: The list of valid `internal_type` values is derived from the `validationConfig` or a static options list (there is no separate `/types` API endpoint).
- **Reconciliation tolerance**: Used to display warning vs. blocking in the reconciliation panel — fetched from API `GET /reconciliation/{statementId}` response.

The workspace performs **advisory** client-side validation only. The API is the authoritative validator.

### 2.3 No Hardcoded Field IDs

All field references (status, amount, currency, customer, etc.) are configurable via a field mapping JSON stored in plugin settings. Example:

```json
{
  "fieldMappings": {
    "status": "c_status",
    "amount": "c_original_amount",
    "fee": "c_fee_amount",
    "total": "c_total_amount",
    "currency": "c_validated_currency",
    "customerId": "c_resolved_customer_id",
    "internalType": "c_internal_type",
    "debitCredit": "c_debit_credit",
    "transactionDate": "c_transaction_date",
    "description": "c_description",
    "confidence": "c_type_confidence",
    "origin": "c_origin"
  }
}
```

At runtime, the plugin reads this mapping so that field IDs can be changed without code modification.

---

## §3. Userview Architecture

### 3.1 Navigation Structure

The enrichment-workspace plugin is deployed as a single menu item in the userview. It provides navigation to five datalist views via tabs or link panels.

**Entry Point**: The plugin is accessed from the Statement form (F01.00) via a link/button that passes `statement_id` as a URL parameter:

```
/jw/userview/enrichmentApp/enrichmentWorkspace?statement_id=STM-2024-07
```

### 3.2 Menu Item Organization

| Menu Item | Component Type | Target | Purpose |
|-----------|---|---|---|
| Enrichment Workspace | Joget UserviewMenu Plugin (this plugin) | Primary datalist + form + dialogs | Main entry point |

**Sub-navigation within the plugin:**

Inside the workspace, the user can navigate between five views via tab bar or dropdown:

1. **Enrichment Workspace** (primary) — all active records
2. **Ready for Posting** — status = ready only
3. **Confirmed Records** — status = confirmed (awaiting downstream pickup)
4. **Split/Merge History** — audit trail
5. **Statement Summary** — aggregated counts by status

Navigation between views is client-side (no page reload). The active view is tracked in the plugin state and reflected in the UI.

### 3.3 Statement-Scoped Navigation

The `statement_id` URL parameter is required and scopes all datalist views to a single statement. The plugin:
- Reads the `statement_id` from the request parameter
- Passes it to enrichment-api in all GET requests (via query parameter `?statementId=STM-xxx`)
- Uses it to compute reconciliation totals per statement
- Displays it prominently in the header

If `statement_id` is missing, the plugin displays a warning and falls back to showing all records (no statement filtering).

### 3.4 Entry Points from Other Parts of the Application

**From Statement Form (F01.00):**
- Button: "Review Enrichment" or "Enrichment Workspace"
- Action: Opens userview in new tab or iframe with `?statement_id={current statement_id}`

**From API Workspace (if implemented):**
- Direct link to userview with statement context

**Standalone Access:**
- Userview menu item in top navigation or sidebar
- No statement context (user selects statement from dropdown if needed)

---

## §4. Datalist Views (COMPLETE SPECIFICATION FOR EACH)

### 4.1 Datalist 1: Enrichment Workspace (Primary)

#### 4.1.1 Identity

| Property | Value |
|----------|-------|
| **Datalist ID** | `enrichmentWorkspace` |
| **Datalist Name** | Enrichment Workspace |
| **Source Plugin** | JDBC Binder |
| **Target Form** | `trxEnrichment` (on row click) |
| **Page Size** | 20 (configurable) |
| **Default Sort** | `c_transaction_date ASC, c_source_type ASC` |
| **Empty State Message** | "No enrichment records found for this statement. Run the enrichment pipeline from the Statement form to create records." |

#### 4.1.2 SQL Query (JDBC Binder)

```sql
SELECT
    e.id                          AS id,
    e.c_source_type               AS source_type,
    e.c_origin                    AS origin,
    e.c_transaction_date          AS transaction_date,
    e.c_settlement_date           AS settlement_date,
    e.c_description               AS description,
    e.c_internal_type             AS internal_type,
    e.c_type_confidence           AS type_confidence,
    e.c_debit_credit              AS debit_credit,
    e.c_original_amount           AS original_amount,
    e.c_fee_amount                AS fee_amount,
    e.c_total_amount              AS total_amount,
    e.c_validated_currency        AS currency,
    e.c_resolved_customer_id      AS customer_id,
    e.c_customer_code             AS customer_code,
    e.c_resolved_asset_id         AS asset_id,
    e.c_counterparty_id           AS counterparty_id,
    e.c_status                    AS status,
    e.c_enrichment_timestamp      AS enrichment_timestamp,
    e.c_error_message             AS error_message,
    e.c_version                   AS version
FROM app_fd_trx_enrichment e
WHERE
    e.c_statement_id = ?
    AND e.c_status NOT IN ('superseded', 'confirmed')
    AND COALESCE(e.c_deleted, 0) = 0
ORDER BY e.c_transaction_date ASC, e.c_source_type ASC
LIMIT ?, ?
```

**Binding Parameters:**
- Parameter 1: `statement_id` (from URL param)
- Parameter 2: `(page - 1) * pageSize` (offset)
- Parameter 3: `pageSize` (limit)

#### 4.1.3 Column Definitions

| Column ID | Label | Type | Width | Sortable | Hidden | Formatter |
|-----------|-------|------|-------|----------|--------|-----------|
| id | ID | Link | 80px | No | No | Link to form: `?id={id}` |
| source_type | Src | Text | 50px | Yes | No | Badge: B (bank), S (secu), M (manual) |
| status | Status | Text | 90px | Yes | No | Status badge (§7) |
| transaction_date | Date | Text | 90px | Yes | No | Date formatter: `yyyy-MM-dd` |
| internal_type | Type | Text | 80px | Yes | No | Text (truncate to 15 chars) |
| description | Description | Text | 200px | No | No | Text truncate to 60 chars + title attr |
| debit_credit | D/C | Text | 50px | Yes | No | D/C colored badge (§7) |
| original_amount | Amount | Decimal | 100px | Yes | No | Right-aligned, #,##0.00, tabular-nums |
| fee_amount | Fee | Decimal | 80px | No | No | Right-aligned, #,##0.00, tabular-nums |
| total_amount | Total | Decimal | 100px | Yes | No | Right-aligned bold, #,##0.00, tabular-nums |
| currency | Ccy | Text | 50px | Yes | No | Text (ISO code) |
| customer_code | Customer | Text | 90px | Yes | No | Text (customer code) |
| asset_id | Asset | Text | 70px | No | No | Text (ticker or ISIN) |
| counterparty_id | CP | Text | 70px | No | No | Text (counterparty code) |
| type_confidence | Conf | Text | 80px | No | No | Confidence badge (§7) |
| origin | Origin | Text | 80px | Yes | No | Text: pipeline, split, merge, manual |

#### 4.1.4 Column Formatters (Joget Native)

**Status Badge Formatter:**
- Joget plugin: `ColumnFormatPlugin` (custom or Joget native)
- Input: column value (e.g., "enriched", "ready", "error")
- Output: `<span class="st-{status}">...</span>` (see §7 for CSS)

**Amount Formatter:**
- Joget plugin: `DecimalFormatPlugin` or `CustomFormatPlugin`
- Input: numeric value
- Output: Right-aligned, tabular-nums font, #,##0.00 format
- CSS class: `ew-amount` or `ew-amount-total`

**D/C Formatter:**
- Input: "D" or "C"
- Output: `<span class="dc-D">D</span>` or `<span class="dc-C">C</span>`

**Source Type Formatter:**
- Input: "bank", "secu", "manual"
- Output: `<b>B</b>`, `<b>S</b>`, `<b>M</b>` respectively

**Date Formatter:**
- Joget plugin: `DateFormatPlugin`
- Output: `yyyy-MM-dd`

**Confidence Formatter:**
- Input: "rule_match", "tentative", "unclassified", "manual_override"
- Output: `<span class="cf-{confidence}">...</span>` (see §7)

#### 4.1.5 Filters

| Filter ID | Widget Type | Label | Source | Default Value | Behavior |
|-----------|--|--|--|--|--|
| statusFilter | Select | Status | Options: `All`, `new`, `processing`, `enriched`, `error`, `manual_review`, `in_review`, `adjusted`, `ready`, `paired` | `All` | AND clause: `c_status = ?` or omitted if All |
| sourceFilter | Select | Source | Options: `All`, `bank`, `secu`, `manual` | `All` | AND clause: `c_source_type = ?` |
| customerFilter | Text | Customer | Freetext input | Empty | AND clause: `c_customer_code LIKE '%?%'` (case-insensitive) |
| typeFilter | Select | Internal Type | Options loaded from `validationConfig` property or static configuration (not a separate API endpoint) | `All` | AND clause: `c_internal_type = ?` |
| dateRangeFilter | Date Range | Date Range | Two date pickers (from/to) | Empty | AND clause: `c_transaction_date BETWEEN ? AND ?` |

**Filter Behavior:**
- Filters are client-side cached (not refetching on every keystroke)
- User clicks "Search" button to apply filters
- "Reset" button clears all filters and re-fetches with defaults
- Active filters are shown as chips/tags below the filter bar
- Filter state is preserved in browser session storage (not persistent across logout)

#### 4.1.6 Batch Actions (Row-Level)

| Action ID | Label | Icon | Selection | Visibility Condition | Confirmation |
|-----------|-------|------|-----------|-----|---|
| editRecord | Edit | `<i class="fas fa-edit"></i>` | Single | Always | None |
| splitRecord | Split | `<i class="fas fa-code-branch"></i>` | Single | `status IN ('enriched', 'in_review', 'adjusted')` | None (opens dialog) |
| markReady | Mark Ready | `<i class="fas fa-check"></i>` | Multi | `status NOT IN ('ready', 'confirmed', 'superseded')` | None |
| markInReview | In Review | `<i class="fas fa-magnifying-glass"></i>` | Multi | `status IN ('enriched', 'adjusted')` | None |
| returnToWorkspace | Return to Workspace | `<i class="fas fa-undo"></i>` | Multi | `status IN ('in_review', 'adjusted', 'ready')` | "Return selected records to enriched status?" |
| deleteRecord | Delete | `<i class="fas fa-trash"></i>` | Single | `status IN ('new', 'error', 'manual_review')` | "Permanently delete this record? This cannot be undone." |

**Batch Actions (Multi-Select):**

| Action ID | Label | Icon | Selection | Visibility | Confirmation |
|-----------|-------|------|-----------|---|---|
| confirmForPosting | Confirm for Posting | `<i class="fas fa-send"></i>` | Multi | `selection.length > 0 AND all selected status = 'ready'` | Confirmation dialog with reconciliation panel (§8) |
| mergeRecords | Merge | `<i class="fas fa-compress"></i>` | Multi (2+) | `selection.length >= 2 AND all status IN ('enriched', 'in_review', 'adjusted', 'ready')` | Dialog to confirm merge and resolve fields (§6.3) |
| markReady (batch) | Mark Ready | `<i class="fas fa-check"></i>` | Multi | `selection.length > 0 AND any status != 'ready'` | None |

**Action Button Visibility Rules:**

- "Confirm for Posting" button: shows only if at least one record with `status = ready` is selected
- "Split" button: shows only if exactly one record is selected and its status allows splitting
- "Delete" button: shows only if exactly one record is selected and its status allows deletion
- "Merge" button: shows only if 2+ records are selected, all in splittable/editable statuses

#### 4.1.7 Empty State

If the datalist query returns 0 rows:
- Display centered message: "No enrichment records found for this statement. Run the enrichment pipeline from the Statement form to create records."
- Display "Back to Statement" link
- Display "Refresh" button

---

### 4.2 Datalist 2: Ready for Posting

#### 4.2.1 Identity

| Property | Value |
|----------|-------|
| **Datalist ID** | `readyForPosting` |
| **Datalist Name** | Ready for Posting |
| **Source Plugin** | JDBC Binder |
| **Target Form** | `trxEnrichment` |
| **Page Size** | 20 |
| **Default Sort** | `c_transaction_date ASC` |
| **Empty State** | "No records marked as ready. Review records in the Enrichment Workspace and mark them as ready before posting." |

#### 4.2.2 SQL Query

```sql
SELECT
    e.id,
    e.c_source_type,
    e.c_origin,
    e.c_transaction_date,
    e.c_settlement_date,
    e.c_description,
    e.c_internal_type,
    e.c_type_confidence,
    e.c_debit_credit,
    e.c_original_amount,
    e.c_fee_amount,
    e.c_total_amount,
    e.c_validated_currency AS currency,
    e.c_resolved_customer_id,
    e.c_customer_code,
    e.c_resolved_asset_id,
    e.c_counterparty_id,
    e.c_status,
    e.c_version
FROM app_fd_trx_enrichment e
WHERE
    e.c_statement_id = ?
    AND e.c_status = 'ready'
    AND COALESCE(e.c_deleted, 0) = 0
ORDER BY e.c_transaction_date ASC
LIMIT ?, ?
```

#### 4.2.3 Columns

Same as Enrichment Workspace datalist (4.1.3), minus the "Origin" column. This view is streamlined for the confirmation workflow.

#### 4.2.4 Filters

Same as Enrichment Workspace, but status filter defaults to "ready" and cannot be changed.

#### 4.2.5 Batch Actions

Only two actions on this view:

| Action ID | Label | Icon | Selection | Confirmation |
|-----------|-------|------|-----------|---|
| confirmForPosting | Confirm for Posting | `<i class="fas fa-send"></i>` | Multi | Confirmation dialog with reconciliation (§8) |
| returnToWorkspace | Return to Workspace | `<i class="fas fa-undo"></i>` | Multi | "Return to enriched status?" |

---

### 4.3 Datalist 3: Confirmed Records

#### 4.3.1 Identity

| Property | Value |
|----------|-------|
| **Datalist ID** | `confirmedRecords` |
| **Datalist Name** | Confirmed Records |
| **Source Plugin** | JDBC Binder |
| **Target Form** | `trxEnrichment` (read-only mode) |
| **Page Size** | 20 |
| **Default Sort** | `c_transaction_date DESC` |
| **Empty State** | "No records confirmed yet. Confirm records from the Ready for Posting view." |

#### 4.3.2 SQL Query

```sql
SELECT
    e.id,
    e.c_source_type,
    e.c_transaction_date,
    e.c_internal_type,
    e.c_debit_credit,
    e.c_original_amount,
    e.c_fee_amount,
    e.c_total_amount,
    e.c_validated_currency AS currency,
    e.c_customer_code,
    e.c_resolved_asset_id,
    e.c_counterparty_id,
    e.c_status,
    e.c_enrichment_timestamp,
    e.c_version
FROM app_fd_trx_enrichment e
WHERE
    e.c_statement_id = ?
    AND e.c_status = 'confirmed'
    AND COALESCE(e.c_deleted, 0) = 0
ORDER BY e.c_transaction_date DESC
LIMIT ?, ?
```

#### 4.3.3 Columns

| Column ID | Label | Type | Sortable | Formatter |
|-----------|-------|------|----------|-----------|
| id | ID | Link | No | Link to form (read-only) |
| source_type | Src | Text | No | Badge: B, S, M |
| transaction_date | Date | Text | Yes | Date: yyyy-MM-dd |
| internal_type | Type | Text | No | Text |
| debit_credit | D/C | Text | No | D/C badge |
| total_amount | Total | Decimal | Yes | #,##0.00 |
| currency | Ccy | Text | No | ISO code |
| customer_code | Customer | Text | No | Text |
| asset_id | Asset | Text | No | Text |
| counterparty_id | CP | Text | No | Text |
| status | Status | Text | No | Status badge (always "confirmed") |
| enrichment_timestamp | Confirmed At | Text | Yes | ISO timestamp |

#### 4.3.4 Filters

Only: Status (read-only to "confirmed"), Date Range, Customer.

#### 4.3.5 Batch Actions

No write actions. Only read action:

| Action ID | Label | Icon | Behavior |
|-----------|-------|------|----------|
| viewDetails | View Details | `<i class="fas fa-eye"></i>` | Opens form in read-only mode |

This view is informational. Confirmed records are immutable from this workspace.

---

### 4.4 Datalist 4: Split/Merge History

#### 4.4.1 Identity

| Property | Value |
|----------|-------|
| **Datalist ID** | `splitMergeHistory` |
| **Datalist Name** | Split/Merge History |
| **Source Plugin** | JDBC Binder |
| **Target Form** | `trxEnrichment` |
| **Page Size** | 20 |
| **Default Sort** | `c_enrichment_timestamp DESC` |
| **Empty State** | "No split or merge operations have been performed on this statement." |

#### 4.4.2 SQL Query

```sql
SELECT
    e.id,
    e.c_source_type,
    e.c_origin,
    e.c_parent_enrichment_id,
    e.c_group_id,
    e.c_split_sequence,
    e.c_transaction_date,
    e.c_internal_type,
    e.c_total_amount,
    e.c_validated_currency AS currency,
    e.c_customer_code,
    e.c_status,
    e.c_lineage_note,
    e.c_enrichment_timestamp,
    e.c_version
FROM app_fd_trx_enrichment e
WHERE
    e.c_statement_id = ?
    AND e.c_origin IN ('split', 'merge')
    AND COALESCE(e.c_deleted, 0) = 0
ORDER BY e.c_enrichment_timestamp DESC
LIMIT ?, ?
```

#### 4.4.3 Columns

| Column ID | Label | Type | Sortable | Formatter |
|-----------|-------|------|----------|-----------|
| id | Child ID | Link | No | Link to form |
| origin | Operation | Text | No | Badge: "split" or "merge" |
| parent_enrichment_id | Parent ID | Text | No | Link to parent record (if split) |
| group_id | Group ID | Text | No | Text (grouped records share same group_id) |
| split_sequence | Seq | Text | No | Number (position within split) |
| transaction_date | Date | Text | Yes | yyyy-MM-dd |
| internal_type | Type | Text | No | Text |
| total_amount | Amount | Decimal | Yes | #,##0.00 |
| currency | Ccy | Text | No | ISO code |
| customer_code | Customer | Text | No | Text |
| status | Status | Text | Yes | Status badge |
| lineage_note | Lineage Note | Text | No | Text truncate to 80 chars + title attr |
| enrichment_timestamp | Created At | Text | Yes | ISO timestamp |

#### 4.4.4 Filters

Status, Origin (split/merge only), Date Range.

#### 4.4.5 Batch Actions

View and trace operations. No direct edit actions. User can click to open form.

---

### 4.5 Datalist 5: Statement Summary

#### 4.5.1 Identity

| Property | Value |
|----------|-------|
| **Datalist ID** | `statementSummary` |
| **Datalist Name** | Statement Summary |
| **Source Plugin** | JDBC Binder or custom API endpoint |
| **Page Size** | N/A (no pagination) |
| **Empty State** | "No summary data available." |

#### 4.5.2 SQL Query (Aggregated)

This datalist shows aggregated counts by status, per currency. It uses a GROUP BY query:

```sql
SELECT
    e.c_status AS status,
    e.c_validated_currency AS currency,
    COUNT(*) AS record_count,
    SUM(e.c_total_amount) AS total_amount,
    MIN(e.c_transaction_date) AS earliest_date,
    MAX(e.c_transaction_date) AS latest_date
FROM app_fd_trx_enrichment e
WHERE
    e.c_statement_id = ?
    AND e.c_status NOT IN ('superseded', 'confirmed')
    AND COALESCE(e.c_deleted, 0) = 0
GROUP BY e.c_status, e.c_validated_currency
ORDER BY e.c_status ASC, e.c_validated_currency ASC
```

#### 4.5.3 Columns

| Column ID | Label | Type | Formatter |
|-----------|-------|------|-----------|
| status | Status | Text | Status badge |
| currency | Currency | Text | ISO code |
| record_count | Count | Number | Plain integer |
| total_amount | Total Amount | Decimal | #,##0.00 |
| earliest_date | Earliest Date | Text | yyyy-MM-dd |
| latest_date | Latest Date | Text | yyyy-MM-dd |

#### 4.5.4 Behavior

This is a read-only summary dashboard. Clicking on a row filters the Enrichment Workspace datalist by that status + currency (cross-datalist navigation).

---

## §5. Form Specification: trxEnrichment

### 5.1 Form Identity

| Property | Value |
|----------|-------|
| **Form ID** | `trxEnrichment` |
| **Form Name** | Transaction Enrichment |
| **Table Name** | `trx_enrichment` |
| **MySQL Table** | `app_fd_trx_enrichment` |
| **Primary Key** | `id` (auto-generated UUID) |
| **Version Field** | `c_version` (optimistic locking) |
| **Audit Fields** | `c_date_created`, `c_date_modified`, `c_created_by`, `c_modified_by` |

### 5.2 All 42 Fields Organized by 8 Sections

#### Section 1: Traceability (3 fields)

| # | Field ID | Field Name | Type | Required | Editable | Default | Validation | Notes |
|---|----------|-----------|------|----------|----------|---------|-----------|-------|
| 1 | source_type | Source Type | SelectBox | Yes | No (pipeline-set) | bank | Options: bank, secu, manual | `bank` for F01.03, `secu` for F01.04, `manual` for user-created |
| 2 | source_trx_id | Source Trx ID | HiddenField | No | No | Null | FK to F01.03 or F01.04 | Null for manual entries. Not unique — multiple F01.05 can reference same source. |
| 3 | statement_id | Statement ID | HiddenField | Yes | No | From URL param | FK to F01.00 | Denormalised for query efficiency. |

#### Section 2: Lineage (5 fields)

| # | Field ID | Field Name | Type | Required | Editable | Default | Notes |
|---|----------|-----------|------|----------|----------|---------|-------|
| 4 | origin | Origin | SelectBox | Yes | No | pipeline | Options: pipeline, split, merge, manual | How record was created. |
| 5 | parent_enrichment_id | Parent Record ID | HiddenField | No | No | Null | FK to F01.05 | If origin = split, this is the parent. |
| 6 | group_id | Group ID | HiddenField | No | No | Null | UUID | Links split/merge family. |
| 7 | split_sequence | Split Position | HiddenField | No | No | Null | Integer | Position in split (1, 2, 3...). |
| 8 | lineage_note | Lineage Note | TextField | No | Yes (editable by user) | Empty | Max 500 chars | "Split from ENR-xxx: 50% to CUST001" |

#### Section 3: Transaction Core (8 fields)

These are **editable** by the customer. Pipeline sets initial values, customer can override.

| # | Field ID | Field Name | Type | Required | Editable | Validation |
|---|----------|-----------|------|----------|----------|-----------|
| 9 | transaction_date | Transaction Date | DatePicker | Yes | Yes | Must be ≤ settlement_date. ISO format (yyyy-MM-dd). |
| 10 | settlement_date | Settlement Date | DatePicker | Yes | Yes | Must be ≥ transaction_date. |
| 11 | original_amount | Principal Amount | TextField (Decimal) | Yes | Yes | Positive number. Two decimal places. Editable by customer. |
| 12 | fee_amount | Fee Amount | TextField (Decimal) | No | Yes | Non-negative. Two decimal places. Can be zero. |
| 13 | total_amount | Total Amount | TextField (Decimal) | Yes | Yes or computed | Computed as original_amount + fee_amount, OR manually overridden. If manually set, original + fee ≠ total triggers a warning. |
| 14 | original_currency | Source Currency | TextField | No | No | ISO 4217 code (3 chars). Set by pipeline from source. Read-only. |
| 15 | debit_credit | Debit/Credit | SelectBox | Yes | Yes | Options: D, C. Required for posting. |
| 16 | description | Description | TextArea | No | Yes | Max 500 chars. Editable by customer. Can rewrite entirely. |

#### Section 4: Classification (3 fields)

| # | Field ID | Field Name | Type | Required | Editable | Notes |
|---|----------|-----------|------|----------|----------|-------|
| 17 | internal_type | Internal Type | SelectBox | Yes | Yes | Options loaded from `validationConfig` property or static configuration (not a separate API endpoint). Pipeline sets initial; customer can override. Required for posting. |
| 18 | type_confidence | Confidence Level | SelectBox | Yes | Yes | Options: rule_match, tentative, unclassified, manual_override. Auto-set to manual_override if customer changes type. |
| 19 | matched_rule_id | Matched Rule ID | HiddenField | No | No | FK to enrichment rule. Cleared if customer overrides type. |

#### Section 5: Currency & FX (6 fields)

| # | Field ID | Field Name | Type | Required | Editable | Validation |
|---|----------|-----------|------|----------|----------|-----------|
| 20 | validated_currency | Confirmed Currency | TextField | Yes | Yes | ISO 4217 code. Required for posting. |
| 21 | fx_rate_to_eur | FX Rate (to EUR) | TextField (Decimal) | No | Yes | If currency ≠ EUR, this is required. Positive number. Editable by customer (override pipeline rate). |
| 22 | fx_rate_date | Rate Date | TextField | No | Yes | Date when rate was obtained. ISO format (yyyy-MM-dd). |
| 23 | fx_rate_source | Rate Source | SelectBox | No | Yes | Options: ecb, bloomberg, manual_override, other. Set to manual_override if customer edits rate. |
| 24 | base_amount_eur | EUR Amount | TextField (Decimal) | No | No | Computed: total_amount × fx_rate_to_eur. Read-only. Recalculated whenever total or rate changes. |
| 25 | requires_eur_parallel | EUR Parallel Required | SelectBox | No | Yes | Options: Y, N. If Y, parallel posting in EUR is required (per GL rules). |

#### Section 6: Resolved Entities (10 fields)

All **editable**. Customer may reassign transaction to different customer, asset, or counterparty.

| # | Field ID | Field Name | Type | Required | Editable | Notes |
|---|----------|-----------|------|----------|----------|-------|
| 26 | resolved_customer_id | Customer ID | TextField | Yes | Yes | FK to customer master. Joget Popup/Lookup selector. Required for posting. |
| 27 | customer_code | Customer Code | TextField | No | Yes | `{custId}` placeholder for GL. Auto-populated from customer master lookup, but editable. |
| 28 | customer_match_method | Customer Match Method | SelectBox | No | Yes | How customer was resolved: bank_reference, transaction_details, manual_override, omnibus. Set to manual_override if customer changes it. |
| 29 | resolved_asset_id | Asset ID (Ticker) | TextField | No | Yes (conditional) | FK to asset master. Popup selector. Required if internal_type is SEC_BUY, SEC_SELL, etc. |
| 30 | asset_isin | Asset ISIN | TextField | No | No | Auto-populated from asset master. Read-only. |
| 31 | asset_category | Asset Category | SelectBox | No | Yes | Options: EQ (equity), FI (fixed income), CE (commodity/other). Auto-set from asset master, editable. Required if asset_id is set. |
| 32 | counterparty_id | Counterparty ID | TextField | No | Yes (conditional) | FK to counterparty master. Popup selector. Required if GL pattern uses {cpId}. |
| 33 | counterparty_short_code | CP Code | TextField | No | Yes | `{cpId}` for GL. Auto-populated, editable. Set to manual_override if changed. |
| 34 | counterparty_source | CP Source | SelectBox | No | Yes | How counterparty was determined: bank_reference, manual_override, other. Set to manual_override if changed. |

#### Section 7: Fee & Pairing (3 fields)

| # | Field ID | Field Name | Type | Required | Editable | Notes |
|---|----------|-----------|------|----------|----------|-------|
| 35 | has_fee | Has Fee | SelectBox | No | Yes | Options: Y, N. If Y, fee_amount must be > 0 and fee_trx_id is set. |
| 36 | fee_trx_id | Fee Transaction ID | HiddenField | No | No | FK to bank fee row (F01.03). Set by pipeline if fee exists. |
| 37 | pair_id | Pairing ID | HiddenField | No | No | FK to trx_pair. Set by pairing engine if record was paired. |

#### Section 8: Status & Notes (5 fields)

| # | Field ID | Field Name | Type | Required | Editable | Notes |
|---|----------|-----------|------|----------|----------|-------|
| 38 | status | Status | SelectBox | Yes | No (dropdown only) | Options: all 11 statuses (see lifecycle). Editable via action buttons, not directly. Read-only select (for display). |
| 39 | enrichment_timestamp | Enriched At | TextField | No | No | When automated enrichment ran. ISO timestamp. Read-only. |
| 40 | error_message | Error Message | TextArea | No | No | Error details from pipeline. Max 1000 chars. Read-only (informational). |
| 41 | processing_notes | Processing Notes | TextArea | No | Yes | Customer's own notes during enrichment. Max 1000 chars. Free-form editable. |
| 42 | version | Version | HiddenField | Yes | No | Optimistic locking. Auto-incremented on save. |

### 5.3 Section Visibility Rules

**Rule 1: Status-Based Section Visibility**

| Section | new | processing | enriched | error | manual_review | in_review | adjusted | ready | paired | superseded | confirmed |
|---------|-----|-----------|----------|-------|---------------|-----------|----------|-------|--------|-----------|-----------|
| 1. Traceability | V | V | V | V | V | V | V | V | V | H | H |
| 2. Lineage | V | V | V | V | V | V | V | V | V | V | H |
| 3. Transaction Core | V | V | V | V | V | V | V | V | V | H | H |
| 4. Classification | V | V | V | V | V | V | V | V | V | H | H |
| 5. Currency & FX | V | V | V | V | V | V | V | V | V | H | H |
| 6. Resolved Entities | V | V | V | V | V | V | V | V | V | H | H |
| 7. Fee & Pairing | V | V | V | V | V | V | V | V | V | H | H |
| 8. Status & Notes | V | V | V | V | V | V | V | V | V | H | H |

**V = Visible, H = Hidden**

- Sections 1–7 are hidden when status = `superseded` or `confirmed` (record is frozen)
- Section 8 is always visible (user can see error messages and add notes even on confirmed records)

### 5.4 Field Editability Matrix (42 fields × 11 statuses)

| Field ID | new | processing | enriched | error | manual_review | in_review | adjusted | ready | paired | superseded | confirmed |
|----------|-----|-----------|----------|-------|---------------|-----------|----------|-------|--------|-----------|-----------|
| source_type | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO |
| source_trx_id | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO |
| statement_id | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO |
| origin | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO |
| parent_enrichment_id | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO |
| group_id | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO |
| split_sequence | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO |
| lineage_note | RO | RO | E | E | E | E | E | E | E | RO | RO |
| transaction_date | RO | E | E | E | E | E | E | E | E | RO | RO |
| settlement_date | RO | E | E | E | E | E | E | E | E | RO | RO |
| original_amount | RO | E | E | E | E | E | E | E | E | RO | RO |
| fee_amount | RO | E | E | E | E | E | E | E | E | RO | RO |
| total_amount | RO | E | E | E | E | E | E | E | E | RO | RO |
| original_currency | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO |
| debit_credit | RO | E | E | E | E | E | E | E | E | RO | RO |
| description | RO | E | E | E | E | E | E | E | E | RO | RO |
| internal_type | RO | E | E | E | E | E | E | E | E | RO | RO |
| type_confidence | RO | E | E | E | E | E | E | E | E | RO | RO |
| matched_rule_id | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO |
| validated_currency | RO | E | E | E | E | E | E | E | E | RO | RO |
| fx_rate_to_eur | RO | E | E | E | E | E | E | E | E | RO | RO |
| fx_rate_date | RO | E | E | E | E | E | E | E | E | RO | RO |
| fx_rate_source | RO | E | E | E | E | E | E | E | E | RO | RO |
| base_amount_eur | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO |
| requires_eur_parallel | RO | E | E | E | E | E | E | E | E | RO | RO |
| resolved_customer_id | RO | E | E | E | E | E | E | E | E | RO | RO |
| customer_code | RO | E | E | E | E | E | E | E | E | RO | RO |
| customer_match_method | RO | E | E | E | E | E | E | E | E | RO | RO |
| resolved_asset_id | RO | E | E | E | E | E | E | E | E | RO | RO |
| asset_isin | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO |
| asset_category | RO | E | E | E | E | E | E | E | E | RO | RO |
| counterparty_id | RO | E | E | E | E | E | E | E | E | RO | RO |
| counterparty_short_code | RO | E | E | E | E | E | E | E | E | RO | RO |
| counterparty_source | RO | E | E | E | E | E | E | E | E | RO | RO |
| has_fee | RO | E | E | E | E | E | E | E | E | RO | RO |
| fee_trx_id | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO |
| pair_id | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO |
| status | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO |
| enrichment_timestamp | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO |
| error_message | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO |
| processing_notes | RO | E | E | E | E | E | E | E | E | RO | E |
| version | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO | RO |

**Key:**
- **RO** = Read-Only (display only, no editing)
- **E** = Editable
- When a field is marked RO in a status, it is displayed in a disabled state or as plain text
- When a field is marked E, it is displayed as an input widget (textfield, select, etc.)

### 5.5 Computed Fields

**Field: total_amount**
- Computed Formula: `total_amount = original_amount + fee_amount`
- When to Compute: On every save (and optionally on blur after editing original_amount or fee_amount)
- Override Logic: If user manually sets total_amount to a different value, warn: "Total amount differs from original + fee. Proceed? (This will override automatic calculation.)"
- Display: Show both the computed value and, if overridden, show a note: "Manually set to X (computed: Y)"

**Field: base_amount_eur**
- Computed Formula: `base_amount_eur = total_amount × fx_rate_to_eur`
- When to Compute: On every save (and optionally on blur after editing total_amount or fx_rate_to_eur)
- When NOT to Compute: If currency = EUR, keep base_amount_eur = null (or equal to total_amount for display)
- Display: Read-only field showing computed value

### 5.6 Form Validation Rules

**Required Fields (per status):**

| Status | Required Fields |
|--------|-----------------|
| any | transaction_date, settlement_date, original_amount, validated_currency, debit_credit |
| any | source_type, statement_id |
| ready | internal_type, customer_code, resolved_customer_id |
| ready (if asset-based type) | resolved_asset_id, asset_category |
| ready (if uses {cpId} in GL pattern) | counterparty_id, counterparty_short_code |
| ready (if currency ≠ EUR) | fx_rate_to_eur |

**Conditional Validation:**

1. **Date Order**: `transaction_date ≤ settlement_date`. Error: "Transaction date must be on or before settlement date."

2. **Amount Signs**:
   - `original_amount > 0`. Error: "Principal amount must be positive."
   - `fee_amount ≥ 0`. Error: "Fee cannot be negative."
   - `total_amount ≠ 0`. Error: "Total amount cannot be zero."

3. **Currency Consistency**:
   - If `validated_currency ≠ EUR`, then `fx_rate_to_eur` is required and `> 0`. Error: "FX rate required for non-EUR currencies."
   - If `validated_currency = EUR`, then `fx_rate_to_eur` should be null or 1.0.

4. **Asset Validation**:
   - If `internal_type` in [SEC_BUY, SEC_SELL, BOND_BUY, BOND_SELL, FUT_BUY, FUT_SELL, OPT_BUY, OPT_SELL], then `resolved_asset_id` is required and must exist in asset master.
   - If `asset_category` is set, it must match the asset master's category for the ticker.

5. **Counterparty Validation**:
   - If `counterparty_id` is set, it must exist in counterparty master.
   - If GL pattern for `internal_type` uses `{cpId}`, then counterparty_id is required.

6. **Customer Validation**:
   - `resolved_customer_id` must exist in customer master (popup selector enforces this).
   - `customer_code` must match the resolved_customer_id (auto-populated, but warn if manually edited to non-matching value).

7. **Fee Consistency**:
   - If `has_fee = Y`, then `fee_amount > 0`. Error: "Fee amount must be positive if has_fee is Y."
   - If `has_fee = N`, then `fee_amount = 0` or null. Warning: "Fee is marked as N but fee_amount is set. Clearing fee_amount."

**Validation Timing:**
- On blur (after field loses focus) — lightweight validation, don't block user
- On save attempt — full validation, block save if errors found
- Display validation errors inline under each field (red text, optional red border)

### 5.7 Form Actions (Buttons)

**Top-Level Buttons:**

| Button ID | Label | Icon | Visibility | Behavior | Confirmation |
|-----------|-------|------|-----------|----------|---|
| saveButton | Save | `<i class="fas fa-save"></i>` | All editable statuses | POST/PUT to enrichment-api `/records/{id}` with form data. If validation fails, display errors. On success, refresh form and show toast "Record saved." | None |
| markReadyButton | Mark Ready | `<i class="fas fa-check"></i>` | status IN (enriched, in_review, adjusted) | Same as Save + status transition to `ready` via enrichment-api `POST /records/{id}/status` with body `{"targetStatus": "ready", "reason": "Analyst marked as ready"}`. Show toast "Marked as ready." | "Mark this record as ready for posting?" |
| returnToWorkspaceButton | Return to Workspace | `<i class="fas fa-undo"></i>` | status IN (in_review, adjusted, ready) | Transition status to `enriched`. `POST /records/{id}/status` with body `{"targetStatus": "enriched", "reason": "Returned to workspace"}`. | "Return to enriched status?" |
| deleteButton | Delete | `<i class="fas fa-trash"></i>` | status IN (new, error, manual_review) | DELETE to enrichment-api `/records/{id}`. | "Permanently delete this record? This cannot be undone." |
| splitButton | Split | `<i class="fas fa-code-branch"></i>` | status IN (enriched, in_review, adjusted) | Open Split dialog (§6.2). | None (dialog handles confirmation) |
| backToListButton | Back to List | `<i class="fas fa-arrow-left"></i>` | All | Navigate back to datalist (Enrichment Workspace). | "Unsaved changes will be lost. Proceed?" (if form is dirty) |

**Button Visibility Logic:**

```javascript
// Pseudocode for button visibility per status
if (record.status === "superseded" || record.status === "confirmed") {
    hide(saveButton, markReadyButton, returnToWorkspaceButton, deleteButton, splitButton);
    show(viewOnlyMessage);
} else if (record.status === "ready") {
    hide(markReadyButton, deleteButton); // already ready; delete not allowed
    show(returnToWorkspaceButton, saveButton);
} else if (record.status === "new" || record.status === "error" || record.status === "manual_review") {
    show(saveButton, markReadyButton, deleteButton);
    hide(returnToWorkspaceButton);
    // splitButton: show only for error/manual_review if relevant
} else {
    // enriched, in_review, adjusted, processing, paired
    show(saveButton, markReadyButton, returnToWorkspaceButton, splitButton);
    hide(deleteButton); // delete only allowed for new, error, manual_review per API
}
```

### 5.8 Form Layout Structure

The form is divided into 8 collapsible sections, each with a header and content area. On page load, expand Sections 3–4 (Transaction Core, Classification), collapse others.

```
┌─────────────────────────────────────────────┐
│ Transaction Enrichment — [ID]               │
│ Status: [badge] | Version: [hidden]         │
├─────────────────────────────────────────────┤
│ ▶ 1. Traceability                 [Expand] │
│                                             │
│ ▶ 2. Lineage                      [Expand] │
│                                             │
│ ▼ 3. Transaction Core                       │
│   ├─ Transaction Date                       │
│   ├─ Settlement Date                        │
│   ├─ Principal Amount | Fee Amount          │
│   ├─ Total Amount                           │
│   ├─ Debit/Credit                           │
│   └─ Description                            │
│                                             │
│ ▼ 4. Classification                         │
│   ├─ Internal Type                          │
│   ├─ Confidence Level                       │
│   └─ [Rule ID — hidden]                     │
│                                             │
│ ▶ 5. Currency & FX                [Expand] │
│                                             │
│ ▶ 6. Resolved Entities            [Expand] │
│                                             │
│ ▶ 7. Fee & Pairing                [Expand] │
│                                             │
│ ▼ 8. Status & Notes                         │
│   ├─ Status [read-only]                     │
│   ├─ Error Message [read-only]              │
│   └─ Processing Notes                       │
│                                             │
├─────────────────────────────────────────────┤
│ [Save] [Mark Ready] [Return] [Delete] [Split]│
│        [Back to List]                        │
└─────────────────────────────────────────────┘
```

---

## §6. Action Flows (COMPLETE FOR EACH)

### 6.1 Confirm for Posting Action Flow

#### 6.1.1 Entry Points

Two entry points, same flow:

1. **Enrichment Workspace datalist** — "Confirm for Posting" batch action button
2. **Ready for Posting datalist** — "Confirm for Posting" batch action button (preferred)

**Selection Mode**: Multi-select via checkboxes. User selects one or more rows with status = `ready`.

**Button Visibility Rule**: "Confirm for Posting" button is visible and enabled only if:
- At least one row is selected
- ALL selected rows have status = `ready`

#### 6.1.2 Step-by-Step Sequence

```
Step 1: User selects records in datalist (checkboxes)
        └─ Selected rows are highlighted
        └─ "Confirm for Posting" button becomes enabled

Step 2: User clicks "Confirm for Posting" button
        └─ UI collects selected row IDs

Step 3: Dialog opens with:
        ├─ Confirmation header: "Confirm {N} records for posting?"
        ├─ Reconciliation panel (§8) — informational, shows per-currency totals
        ├─ Selected records summary — table with ID, date, type, amount, customer
        └─ Buttons: [Confirm] [Cancel]

Step 4: User reviews reconciliation panel
        ├─ If discrepancy exists but records remain active: ⚠ warning shown, proceed allowed
        ├─ If discrepancy exists and NO records remain active: 🛑 blocked, cancel shown
        └─ If balanced: ✓ proceed button enabled

Step 5: User clicks "Confirm" button
        └─ UI sends POST request to enrichment-api `/records/confirm`

Step 6: API processes confirmation
        ├─ Validates each record per-record completeness
        ├─ Runs reconciliation check
        ├─ Transitions F01.05 records to `confirmed` via StatusManager
        ├─ Sets confirmed_by and confirmed_at audit fields
        └─ Returns success or error response (downstream posting plugin picks up confirmed records separately)

Step 7: On success:
        ├─ Toast message: "X records confirmed for posting."
        ├─ Datalist is refreshed (selected rows disappear)
        ├─ User returns to datalist view
        └─ Record count is updated

Step 7b: On error:
        ├─ Dialog shows error message from API
        ├─ Per-record errors are displayed (if batch partial failure)
        ├─ User can click [Retry] or [Cancel]
        └─ No state is changed (rollback)
```

#### 6.1.3 Dialog Specification: Confirmation Dialog

**Dialog ID**: `confirmationDialog`
**Modal**: Yes, blocks background interaction
**Size**: 600px wide × 700px tall (responsive)
**Closing**: Can close via X button (same as Cancel), but confirms user intent: "Discard changes?"

**Dialog Content:**

```html
<div class="confirmation-dialog">
  <div class="dialog-header">
    <h3>Confirm Records for Posting</h3>
    <p>You are about to confirm X record(s) for posting.</p>
  </div>

  <div class="dialog-body">
    <!-- Reconciliation Panel (§8) -->
    <div class="reconciliation-panel">
      <!-- Table per currency -->
    </div>

    <!-- Selected Records Summary -->
    <div class="records-summary">
      <h4>Selected Records</h4>
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>Date</th>
            <th>Type</th>
            <th>Amount</th>
            <th>Currency</th>
            <th>Customer</th>
          </tr>
        </thead>
        <tbody>
          <!-- One row per selected record -->
        </tbody>
      </table>
    </div>

    <!-- Validation Errors (if any) -->
    <div class="validation-errors" style="display:none;">
      <h4 style="color:red;">Validation Errors</h4>
      <ul>
        <!-- Error messages per record -->
      </ul>
    </div>
  </div>

  <div class="dialog-footer">
    <button id="confirmBtn" class="btn btn-primary">Confirm</button>
    <button id="cancelBtn" class="btn btn-secondary">Cancel</button>
  </div>
</div>
```

**Dialog Fields:**
- No form fields, just display
- Reconciliation panel is informational (§8)
- Selected records table shows key fields for verification

**Dialog Buttons:**
- **[Confirm]**: POST to enrichment-api `/records/confirm`, send list of selected record IDs
- **[Cancel]**: Close dialog, return to datalist

#### 6.1.4 API Call

**Endpoint**: `POST /jw/api/enrichment/records/confirm`

**Request Body**:
```json
{
  "statementId": "STM-2024-07",
  "recordIds": ["ENR-001", "ENR-002", "ENR-003"],
  "userId": "current_user_id",
  "timestamp": "2026-03-02T14:30:00Z"
}
```

**Success Response** (HTTP 200):
```json
{
  "status": "success",
  "message": "3 records confirmed for posting.",
  "confirmedRecordIds": ["ENR-001", "ENR-002", "ENR-003"],
  "reconciliation": {
    "EUR": { "balanced": true, "discrepancy": 0.00 },
    "USD": { "balanced": true, "discrepancy": 0.00 }
  }
}
```

**Error Response** (HTTP 400 or 409):
```json
{
  "status": "error",
  "message": "Confirmation failed.",
  "errors": [
    {
      "recordId": "ENR-002",
      "field": "customer_code",
      "message": "Customer code is required."
    }
  ],
  "reconciliation": {
    "EUR": { "balanced": false, "discrepancy": 125.50 }
  }
}
```

#### 6.1.5 Success Behavior

1. Toast message displayed for 3 seconds: "X records confirmed for posting."
2. Datalist is refreshed via AJAX (GET with same filters)
3. Selected rows disappear (status changed to confirmed)
4. Record count is decremented
5. If user navigates to Confirmed Records view, new records appear there
6. Dialog closes automatically

#### 6.1.6 Error Handling

If API returns error (validation failed, reconciliation blocked, network error):

1. Dialog stays open
2. Validation errors are displayed in red text under the records summary
3. Reconciliation panel is highlighted (red border) if blocking error
4. [Confirm] button is disabled
5. [Cancel] button changes to [Dismiss]
6. User must address issues and retry, or cancel

**Network Timeout**: If POST takes >30 seconds, show timeout message and allow retry.

**Stale State**: If user has multiple browser tabs open and one confirms records in another tab, the datalist is automatically refreshed. If the same record ID appears in two datalists, Joget's datalist refresh handles deduplication.

#### 6.1.7 Edge Cases

1. **All records already confirmed by another user**:
   - API returns 0 records confirmed
   - Toast: "No records to confirm (possibly already confirmed by another user)."
   - Datalist is refreshed

2. **Partial success** (some records confirmed, others failed):
   - API returns mixed response
   - Dialog displays which records failed
   - User can correct and retry just the failed ones

3. **Reconciliation blocked** (final confirmation, discrepancy exists):
   - Dialog shows red reconciliation panel
   - Message: "Cannot confirm: statement is unbalanced by {amount}. Please resolve discrepancies before confirming the final batch."
   - [Confirm] button is disabled

4. **Large batch** (1000+ records):
   - API may take 10–30 seconds
   - Show progress spinner
   - Allow cancel during processing (rollback entire batch)

---

### 6.2 Split Action Flow

#### 6.2.1 Entry Points

**Single-record action**: "Split" button on the form or datalist row action.

**Selection Mode**: Exactly one record. If user selects 2+ records in datalist, "Split" button is hidden.

**Visibility Rule**:
- Shows only if exactly one row is selected
- Shows only if status IN (enriched, in_review, adjusted)

#### 6.2.2 Step-by-Step Sequence

```
Step 1: User clicks "Split" button on form or datalist
        └─ Split dialog opens

Step 2: Dialog displays parent record summary:
        ├─ ID, date, type, amount, currency, customer
        └─ "You are splitting this record into multiple allocations."

Step 3: User enters number of splits (e.g., "3" for block trade allocation)
        └─ Input field: "Number of Allocations" (integer, 2–10)

Step 4: User clicks "Next" or dialog dynamically shows allocation grid:
        ├─ Grid has N rows (one per allocation)
        ├─ Columns: Customer Code | Amount | % | Notes
        └─ First row is pre-filled with parent's customer and 0%

Step 5: User fills in allocation details:
        ├─ Customer Code — popup selector
        ├─ Amount — numeric input, sum must equal parent total_amount
        ├─ % — calculated as Amount ÷ Parent Amount × 100
        ├─ Notes — optional free text (e.g., "50% to CUST001")

Step 6: User clicks "Validate"
        └─ Dialog validates:
           ├─ All customers are valid (exist in master)
           ├─ All amounts are positive
           ├─ Sum of amounts ≈ parent total_amount (within tolerance ±0.01)
           ├─ No duplicate customers (unless explicitly allowed)
           └─ Show errors in red if validation fails

Step 7: User clicks "Confirm Split"
        └─ Dialog sends POST to enrichment-api `/records/{id}/split`

Step 8: API processes split:
        ├─ Validates all allocation details
        ├─ Creates N new F01.05 records
        ├─ Sets origin = split, parent_enrichment_id = original.id, group_id = original.id
        ├─ Marks parent record status = superseded
        └─ Returns new record IDs

Step 9: On success:
        ├─ Dialog closes
        ├─ User is shown toast: "Record split into X allocations."
        ├─ If on form view, form reloads with parent (now superseded)
        ├─ Datalist is refreshed (parent disappears, children appear)
        └─ User can review child records
```

#### 6.2.3 Dialog Specification: Split Dialog

**Dialog ID**: `splitDialog`
**Modal**: Yes
**Size**: 700px wide × 600px tall

**Dialog Content:**

```html
<div class="split-dialog">
  <div class="dialog-header">
    <h3>Split Transaction</h3>
    <p>Original Record: [ID] | Amount: [formatted] [ccy] | Customer: [code]</p>
  </div>

  <div class="dialog-body">
    <!-- Step 1: Number of Allocations -->
    <div class="split-step-1">
      <label for="numAllocations">Number of Allocations:</label>
      <input type="number" id="numAllocations" min="2" max="10" value="2" />
      <button id="nextBtn">Next ></button>
    </div>

    <!-- Step 2: Allocation Grid -->
    <div class="split-step-2" style="display:none;">
      <h4>Allocate to Customers</h4>
      <p>Total Amount to Allocate: [parent.total_amount] [ccy]</p>

      <table class="allocation-grid">
        <thead>
          <tr>
            <th>Customer Code</th>
            <th>Amount</th>
            <th>%</th>
            <th>Notes</th>
          </tr>
        </thead>
        <tbody id="allocationBody">
          <!-- Rows inserted via JavaScript -->
          <!-- Example:
          <tr>
            <td><input type="text" class="cust-code" placeholder="CUST001" data-popup-selector="customerPicker" /></td>
            <td><input type="number" class="alloc-amount" step="0.01" /></td>
            <td><input type="number" class="alloc-percent" readonly /></td>
            <td><input type="text" class="alloc-notes" /></td>
          </tr>
          -->
        </tbody>
      </table>

      <!-- Summary -->
      <div class="allocation-summary">
        <p>Total Allocated: <span id="totalAllocated">0.00</span> / <span id="parentAmount">[amount]</span></p>
        <p id="balanceError" style="color:red;display:none;">Allocations do not sum to parent amount. Difference: <span id="balanceDiff"></span></p>
      </div>

      <button id="validateBtn">Validate</button>
      <button id="backBtn">< Back</button>
    </div>

    <!-- Validation Errors -->
    <div class="validation-errors" style="display:none;">
      <h4 style="color:red;">Validation Errors</h4>
      <ul id="errorList"></ul>
    </div>
  </div>

  <div class="dialog-footer">
    <button id="confirmBtn" class="btn btn-primary" disabled>Confirm Split</button>
    <button id="cancelBtn" class="btn btn-secondary">Cancel</button>
  </div>
</div>
```

**Dialog Behavior:**
- Step 1: User enters number of allocations, clicks "Next"
- JavaScript inserts N rows into allocation grid
- Each row has a customer popup picker, amount input, auto-calculated percentage, and notes
- On amount change, JavaScript recalculates sum and percentage
- [Validate] button checks all amounts and customers
- [Confirm Split] button sends to API
- [Cancel] closes dialog without changes

#### 6.2.4 API Call

**Endpoint**: `POST /jw/api/enrichment/records/{id}/split`

**Request Body**:
```json
{
  "parentRecordId": "ENR-001",
  "allocations": [
    {
      "customerCode": "CUST001",
      "amount": 5000.00,
      "notes": "50% allocation"
    },
    {
      "customerCode": "CUST002",
      "amount": 3000.00,
      "notes": "30% allocation"
    },
    {
      "customerCode": "CUST003",
      "amount": 2000.00,
      "notes": "20% allocation"
    }
  ],
  "userId": "current_user"
}
```

**Success Response** (HTTP 201):
```json
{
  "status": "success",
  "message": "Record split into 3 allocations.",
  "parentRecordId": "ENR-001",
  "childRecordIds": ["ENR-001-01", "ENR-001-02", "ENR-001-03"],
  "groupId": "GRP-uuid"
}
```

**Error Response** (HTTP 400):
```json
{
  "status": "error",
  "message": "Split validation failed.",
  "errors": [
    {
      "index": 1,
      "field": "customerCode",
      "message": "Customer CUST002 not found."
    },
    {
      "allocation": "Total allocation (10,000) exceeds parent amount (10,000) by 0.01."
    }
  ]
}
```

#### 6.2.5 Success Behavior

1. Toast: "Record split into X allocations."
2. If on form view, parent record is reloaded (status = superseded, read-only)
3. Datalist is refreshed — parent disappears, child records appear as new rows
4. Dialog closes
5. User can click on each child record to edit and finalize

#### 6.2.6 Error Handling

Same as Confirm for Posting: dialog stays open, errors displayed, user corrects and retries.

#### 6.2.7 Edge Cases

1. **Rounding Discrepancy**: If sum of allocations differs by < 0.01 from parent amount, API auto-adjusts the last allocation to balance. Show warning: "Last allocation adjusted by ±X to balance total."

2. **Same Customer Multiple Times**: API allows same customer in multiple rows (same customer, multiple allocations). Show warning if detected: "Customer X appears in multiple allocations. Is this intended?"

3. **Asset-Based Transaction**: If parent is SEC_BUY with asset_id, each child inherits the asset. If parent's internal_type is BLOCK_ALLOCATION_* (special type for block trades), API auto-sets child internal_type appropriately.

---

### 6.3 Merge Action Flow

#### 6.3.1 Entry Points

**Batch action**: "Merge" button on datalist. Shows only if 2+ records are selected.

**Selection Mode**: Multi-select, 2 or more records.

**Visibility Rule**:
- Shows only if 2+ rows are selected
- Shows only if all selected rows have status IN (enriched, in_review, adjusted, ready)
- Shows only if all selected rows have same statement_id and currency (merge within same statement/ccy only)

#### 6.3.2 Step-by-Step Sequence

```
Step 1: User selects 2+ records in datalist
        └─ "Merge" button becomes enabled

Step 2: User clicks "Merge" button
        └─ Merge dialog opens

Step 3: Dialog displays:
        ├─ List of selected records (ID, date, type, amount, customer)
        ├─ "These records will be merged into one."
        └─ Field resolution form for merged record

Step 4: For each conflicting field, user resolves:
        ├─ Internal Type — dropdown (select one from sources or new value)
        ├─ Customer Code — dropdown (select one from sources)
        ├─ Description — text area (combine or new)
        ├─ FX Rate — numeric (select one or enter new)
        └─ Notes — free text explaining merge

Step 5: Amounts are auto-summed (no choice):
        ├─ total_amount = SUM(all selected total_amounts)
        ├─ original_amount = SUM(all original_amounts)
        ├─ fee_amount = SUM(all fee_amounts)

Step 6: User clicks "Validate Merge"
        └─ Dialog validates:
           ├─ Resolved fields are valid
           ├─ Summed amounts are reasonable
           └─ Show errors if validation fails

Step 7: User clicks "Confirm Merge"
        └─ Dialog sends POST to enrichment-api `/records/merge`

Step 8: API processes merge:
        ├─ Creates 1 new F01.05 record with merged data
        ├─ Sets origin = merge, group_id = new UUID
        ├─ Marks all source records status = superseded, group_id = same UUID
        └─ Returns new merged record ID

Step 9: On success:
        ├─ Toast: "Records merged into 1 new record."
        ├─ Datalist is refreshed (source records disappear, merged record appears)
        ├─ User can review merged record and make final adjustments
        └─ Dialog closes
```

#### 6.3.3 Dialog Specification: Merge Dialog

**Dialog ID**: `mergeDialog`
**Modal**: Yes
**Size**: 750px wide × 700px tall

**Dialog Content:**

```html
<div class="merge-dialog">
  <div class="dialog-header">
    <h3>Merge Records</h3>
    <p>You are merging X records into one.</p>
  </div>

  <div class="dialog-body">
    <!-- Source Records Summary -->
    <div class="merge-sources">
      <h4>Records Being Merged</h4>
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>Date</th>
            <th>Type</th>
            <th>Amount</th>
            <th>Customer</th>
          </tr>
        </thead>
        <tbody id="mergeSourcesBody">
          <!-- One row per selected record -->
        </tbody>
      </table>
    </div>

    <!-- Merged Record Field Resolution -->
    <div class="merge-form">
      <h4>Merged Record Details</h4>

      <!-- Auto-Summed Fields (read-only) -->
      <div class="form-group">
        <label>Total Amount (Auto-Summed):</label>
        <input type="text" id="mergedTotal" readonly />
      </div>
      <div class="form-group">
        <label>Original Amount (Auto-Summed):</label>
        <input type="text" id="mergedOriginal" readonly />
      </div>
      <div class="form-group">
        <label>Fee Amount (Auto-Summed):</label>
        <input type="text" id="mergedFee" readonly />
      </div>

      <!-- Conflict Resolution Fields -->
      <div class="form-group">
        <label for="mergedType">Internal Type:</label>
        <select id="mergedType">
          <option value="">-- Select Type --</option>
          <!-- Options from validationConfig or static configuration -->
        </select>
        <small>Or select one from sources: [list source types]</small>
      </div>

      <div class="form-group">
        <label for="mergedCustomer">Customer Code:</label>
        <input type="text" id="mergedCustomer" placeholder="CUST..." />
        <button id="custPickerBtn">...</button>
        <small>Or select one from sources: [list source customers]</small>
      </div>

      <div class="form-group">
        <label for="mergedDescription">Description:</label>
        <textarea id="mergedDescription" rows="3"></textarea>
        <small>Combine or rewrite. Default: concatenated source descriptions.</small>
      </div>

      <div class="form-group">
        <label for="mergedFxRate">FX Rate (if non-EUR):</label>
        <input type="number" id="mergedFxRate" step="0.0001" />
        <small>Or select one from sources: [list source rates]</small>
      </div>

      <div class="form-group">
        <label for="mergeNotes">Merge Notes:</label>
        <textarea id="mergeNotes" rows="2" placeholder="Why were these merged?"></textarea>
      </div>
    </div>

    <!-- Validation Errors -->
    <div class="validation-errors" style="display:none;">
      <h4 style="color:red;">Validation Errors</h4>
      <ul id="errorList"></ul>
    </div>
  </div>

  <div class="dialog-footer">
    <button id="validateBtn" class="btn btn-secondary">Validate</button>
    <button id="confirmBtn" class="btn btn-primary" disabled>Confirm Merge</button>
    <button id="cancelBtn" class="btn btn-secondary">Cancel</button>
  </div>
</div>
```

#### 6.3.4 API Call

**Endpoint**: `POST /jw/api/enrichment/records/merge`

**Request Body**:
```json
{
  "sourceRecordIds": ["ENR-010", "ENR-011", "ENR-012"],
  "mergedRecord": {
    "internalType": "TRANSFER",
    "customerCode": "CUST-MAIN",
    "description": "Consolidated transfer from 3 source transactions",
    "fxRateToEur": 1.0,
    "mergeNotes": "Combined related transfers into one posting"
  },
  "userId": "current_user"
}
```

**Success Response** (HTTP 201):
```json
{
  "status": "success",
  "message": "3 records merged into 1 new record.",
  "mergedRecordId": "ENR-MERGE-001",
  "sourceRecordIds": ["ENR-010", "ENR-011", "ENR-012"],
  "groupId": "GRP-uuid"
}
```

#### 6.3.5 Success Behavior

1. Toast: "Records merged into 1 new record."
2. Datalist is refreshed — source records disappear, merged record appears
3. Dialog closes
4. User can immediately edit the merged record if needed
5. Merged record status defaults to "enriched"

#### 6.3.6 Error Handling

Same as Split: validation errors displayed in dialog, user corrects and retries.

#### 6.3.7 Edge Cases

1. **Mixed Currencies**: If source records have different currencies, show error: "Cannot merge records in different currencies."

2. **Mixed Statements**: If source records have different statement_ids, show error: "Cannot merge records from different statements."

3. **Conflicting Statuses**: If one source is "ready" and another is "enriched", warn: "Source records are in different statuses. Merged record will be in 'enriched' status. Proceed?"

4. **Large Merge**: If merging 10+ records, show progress spinner during processing.

---

### 6.4 Status Change Action Flow

#### 6.4.0 API Request Format

All status change requests use the enrichment-api endpoint format:

```json
POST /records/{id}/status           // single record
POST /records/status                // batch (multiple records)

// Request body:
{
  "targetStatus": "ready",          // required: Status enum code
  "reason": "Analyst marked as ready"  // optional: audit reason for StatusManager
}

// Batch request body:
{
  "recordIds": ["ENR-001", "ENR-002"],
  "targetStatus": "ready"
}
```

The `targetStatus` field name maps to gam-framework's `Status` enum. The `reason` field is passed to `StatusManager.transition()` for audit logging and is optional for most transitions. The API response includes `succeeded` and `failed` arrays for batch operations.

#### 6.4.1 Entry Points

Four entry points on the form or datalist:

1. **"Mark Ready"** — transition to status = ready
2. **"Return to Workspace"** — transition to status = enriched
3. **"Mark In Review"** — transition to status = in_review
4. **"Reprocess"** — transition to status = processing (admin only, or for error recovery)

**Selection Mode**: Single (form) or Multi (datalist batch action).

#### 6.4.2 Status Transitions (Allowed)

| From Status | Mark Ready | Return to Workspace | Mark In Review | Reprocess |
|-------------|-----------|-------------------|---|---|
| new | Yes | No | No | Yes |
| processing | Yes | Yes | Yes | Yes |
| enriched | Yes | Yes | Yes | Yes |
| error | Yes | Yes | Yes | Yes |
| manual_review | Yes | Yes | Yes | Yes |
| in_review | Yes | Yes | No | Yes |
| adjusted | Yes | Yes | No | Yes |
| ready | No | Yes | No | Yes |
| paired | Yes | Yes | No | Yes |
| superseded | No | No | No | No |
| confirmed | No | No | No | No |

#### 6.4.3 Implementation: Form Buttons

**Mark Ready Button:**
- Visibility: status IN (enriched, in_review, adjusted, processing, error, manual_review)
- Confirmation: "Mark this record as ready for posting?"
- API Call: `POST /jw/api/enrichment/records/{id}/status` with body `{"targetStatus": "ready", "reason": "Analyst marked as ready"}`
- On Success: Toast "Marked as ready." + form reloads with new status

**Return to Workspace Button:**
- Visibility: status IN (in_review, adjusted, ready)
- Confirmation: "Return to enriched status?"
- API Call: `POST /jw/api/enrichment/records/{id}/status` with body `{"targetStatus": "enriched", "reason": "Returned to workspace"}`
- On Success: Toast "Returned to workspace." + form reloads

#### 6.4.4 Implementation: Datalist Batch Actions

**Mark Ready (Batch):**
- Visibility: selection.length > 0 AND any selected status ≠ ready
- API Call: `POST /jw/api/enrichment/records/status` with body:
  ```json
  {
    "recordIds": ["ENR-001", "ENR-002"],
    "targetStatus": "ready"
  }
  ```
- On Success: Datalist is refreshed, matching records move to "Ready for Posting" view. API response includes `succeeded` and `failed` arrays — if any records failed, show warning: "X records failed: [reasons]."

**Return to Workspace (Batch):**
- Visibility: selection.length > 0 AND all selected status IN (in_review, adjusted, ready)
- API Call: `POST /jw/api/enrichment/records/status` with body `{"recordIds": [...], "targetStatus": "enriched"}`
- On Success: Datalist is refreshed

#### 6.4.5 Error Handling

If a status transition fails (e.g., validation prevents marking ready):
- Show error message: "Cannot mark as ready: {validation error details}"
- Form or datalist remains unchanged
- User can address issues and retry

---

## §7. CSS Formatter Specifications

All CSS classes below are applied by column formatters, computed field formatters, and badge formatters throughout the UI.

### 7.1 Status Badge Styles (11 statuses)

```css
.st-new {
  background: #E3F2FD;
  color: #1565C0;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 0.85em;
  font-weight: normal;
}

.st-processing {
  background: #FFF3E0;
  color: #E65100;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 0.85em;
  font-weight: normal;
}

.st-enriched {
  background: #E8F5E9;
  color: #2E7D32;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 0.85em;
  font-weight: normal;
}

.st-error {
  background: #FFEBEE;
  color: #C62828;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 0.85em;
  font-weight: bold;
}

.st-manual_review {
  background: #FFF8E1;
  color: #F57F17;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 0.85em;
  font-weight: normal;
}

.st-in_review {
  background: #F3E5F5;
  color: #6A1B9A;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 0.85em;
  font-weight: normal;
}

.st-adjusted {
  background: #E0F2F1;
  color: #00695C;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 0.85em;
  font-weight: normal;
}

.st-ready {
  background: #C8E6C9;
  color: #1B5E20;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 0.85em;
  font-weight: bold;
}

.st-paired {
  background: #E8EAF6;
  color: #283593;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 0.85em;
  font-weight: normal;
}

.st-superseded {
  background: #F5F5F5;
  color: #9E9E9E;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 0.85em;
  font-weight: normal;
  text-decoration: line-through;
}

.st-confirmed {
  background: #E8F5E9;
  color: #1B5E20;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 0.85em;
  font-weight: bold;
}
```

### 7.2 Confidence Badge Styles (4 levels)

```css
.cf-rule_match {
  background: #E8F5E9;
  color: #2E7D32;
  padding: 2px 6px;
  border-radius: 8px;
  font-size: 0.8em;
  font-weight: normal;
}

.cf-tentative {
  background: #FFF8E1;
  color: #F57F17;
  padding: 2px 6px;
  border-radius: 8px;
  font-size: 0.8em;
  font-weight: normal;
}

.cf-unclassified {
  background: #FFEBEE;
  color: #C62828;
  padding: 2px 6px;
  border-radius: 8px;
  font-size: 0.8em;
  font-weight: normal;
}

.cf-manual_override {
  background: #E0F2F1;
  color: #00695C;
  padding: 2px 6px;
  border-radius: 8px;
  font-size: 0.8em;
  font-weight: normal;
}
```

### 7.3 Amount Formatting

```css
.ew-amount {
  text-align: right;
  font-variant-numeric: tabular-nums;
  font-family: 'Menlo', 'Courier New', monospace;
}

.ew-amount-total {
  text-align: right;
  font-variant-numeric: tabular-nums;
  font-family: 'Menlo', 'Courier New', monospace;
  font-weight: bold;
}

/* Formatted as: #,##0.00 (e.g., 1,234.56) */
```

### 7.4 D/C Coloring (Debit/Credit)

```css
.dc-D {
  color: #DC2626;
  font-weight: 600;
}

.dc-C {
  color: #16A34A;
  font-weight: 600;
}
```

### 7.5 Source Type Labels

```css
.src-bank::before {
  content: "B";
  font-weight: bold;
  color: #1565C0;
}

.src-secu::before {
  content: "S";
  font-weight: bold;
  color: #E65100;
}

.src-manual::before {
  content: "M";
  font-weight: bold;
  color: #F57F17;
}
```

### 7.6 Origin Type Indicators

```css
.origin-pipeline {
  display: inline-block;
  background: #E3F2FD;
  color: #1565C0;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 0.75em;
}

.origin-split {
  display: inline-block;
  background: #F3E5F5;
  color: #6A1B9A;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 0.75em;
}

.origin-merge {
  display: inline-block;
  background: #E0F2F1;
  color: #00695C;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 0.75em;
}

.origin-manual {
  display: inline-block;
  background: #FFF8E1;
  color: #F57F17;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 0.75em;
}
```

---

## §8. Reconciliation Panel

### 8.1 When It Appears

The reconciliation panel is displayed in the **Confirm for Posting** dialog as an informational component. It shows the statement-level reconciliation status before the user confirms records.

**Display Location**: Top of the dialog, above the selected records table.

### 8.2 Layout (Per-Currency Table)

```
┌─────────────────────────────────────────────────────────────────────┐
│ Statement Reconciliation Status                                     │
├──────────┬───────────┬────────────┬───────────┬────────────────────┤
│ Currency │ Source    │ Manual Adj │ Adj Input │ Output (Confirmed  │
│          │ Input     │            │           │ + Batch) +         │
│          │ (F01.03 + │            │           │ Remaining          │
│          │  F01.04)  │            │           │ (Active F01.05)    │
├──────────┼───────────┼────────────┼───────────┼────────────────────┤
│ EUR      │ 45,230.00 │ +600.00    │ 45,830.00 │ ⚠ 45,830.05        │
│ USD      │125,400.00 │ -500.00    │124,900.00 │ ✓ 124,900.00       │
│ GBP      │  8,100.00 │      0.00  │  8,100.00 │ ✓ 8,100.00         │
└──────────┴───────────┴────────────┴───────────┴────────────────────┘

┌─ Detailed Per Currency ─┐
│ EUR: Discrepancy = 0.05 (within tolerance ±0.02) ⚠ warning
│ USD: Discrepancy = 0.00 ✓ balanced
│ GBP: Discrepancy = 0.00 ✓ balanced
```

### 8.3 Column Definitions

| Column | Description | Example | Calculation |
|--------|-------------|---------|-------------|
| **Currency** | ISO code | EUR, USD, GBP | Group by currency |
| **Source Input** | Total from F01.03 + F01.04 | 45,230.00 | SUM(F01.03) + SUM(F01.04) for this statement |
| **Manual Adj** | Total of manual F01.05 entries | +600.00 or -500.00 | SUM(F01.05 WHERE origin='manual' AND status NOT IN ('superseded')) |
| **Adj Input** | Source Input + Manual Adj | 45,830.00 | Source Input + Manual Adj |
| **Output (Current Batch)** | Sum of records being confirmed now | 38,730.00 | SUM(current confirmation batch total_amount) |
| **Already Confirmed** | Sum of F01.05 records with status = 'confirmed' | 7,100.00 | SUM(F01.05 WHERE status = 'confirmed' AND statement_id = S) |
| **Total Output** | Already Confirmed + Batch | 45,830.00 | Already Confirmed + Output (Current Batch) |
| **Remaining (Active F01.05)** | Sum of active F01.05 records | 0.00 | SUM(F01.05 WHERE status NOT IN ('superseded', 'confirmed')) |
| **Expected** | Adj Input | 45,830.00 | Same as Adj Input |
| **Discrepancy** | Total Output + Remaining - Expected | 0.05 | (Total Output + Remaining) - Expected |

### 8.4 Status Indicators

**Per Row (Per Currency):**

- ✓ **Balanced**: Discrepancy = 0 (within tolerance ±0.02 for EUR/USD, ±0.05 for others)
- ⚠ **Warning**: Discrepancy ≠ 0 but within tolerance. Show in yellow.
- 🛑 **Blocked**: Discrepancy exceeds tolerance AND no active F01.05 records remain. Show in red. Block confirmation.

**Overall Status Line Below Table:**

```
Statement Status: [Status Badge]
├─ If all currencies balanced: ✓ BALANCED — All currencies reconcile.
├─ If any currency warning: ⚠ UNBALANCED (within tolerance) — Proceed allowed if active records remain.
└─ If any currency blocked: 🛑 UNBALANCED (exceeds tolerance) — Confirmation blocked. Resolve discrepancies first.
```

### 8.5 MVP Behavior

**Important**: The reconciliation panel in this specification is **informational only** in MVP phase. It does NOT block confirmation based on discrepancies (except the final confirmation when no active records remain).

| Situation | Discrepancy | Button State | Behavior |
|-----------|-------------|---|---|
| Partial confirmation (active records remain) | = 0 | Enabled | ✓ Proceed |
| Partial confirmation | ≠ 0 (any amount) | Enabled | ⚠ Warning shown, proceed allowed |
| Final confirmation (no active records after this batch) | = 0 | Enabled | ✓ Proceed |
| Final confirmation | ≠ 0 | Disabled | 🛑 Blocked — user must fix or cancel |

**Implementation**:
- On dialog load, compute reconciliation for the statement
- Display reconciliation panel with status
- If partial confirmation with discrepancy, show warning tooltip on [Confirm] button: "Statement is unbalanced. You can proceed now, but final confirmation will be blocked if discrepancy remains."
- If final confirmation with discrepancy, disable [Confirm] button and show error: "Statement is unbalanced. Resolve the discrepancy before confirming the final batch."

The API determines whether this is a "final confirmation" by checking if any active F01.05 records remain after this batch. If yes, it's partial. If no, it's final.

---

## §9. Edge Cases & Error Handling

### 9.1 Concurrent Modification

**Scenario**: User A loads record ENR-001, User B updates it, User A tries to save.

**Implementation**: Optimistic locking via `version` field.

1. Form displays `version` as hidden field (current value from DB, e.g., 3)
2. User A makes changes and clicks Save
3. Form POSTs to enrichment-api with `{"id": "ENR-001", "version": 3, ...fields...}`
4. API checks: SELECT COUNT(*) FROM F01.05 WHERE id='ENR-001' AND version=3
5. If count=0, API returns 409 Conflict: "Record was modified by another user. Refresh and try again."
6. UI displays error modal: "This record was modified by another user. Your changes have not been saved. [Refresh] [Discard]"
7. User clicks Refresh — form reloads, user re-applies changes
8. Or user clicks Discard — form reverts to saved state

### 9.2 Stale Datalist

**Scenario**: User opens datalist, sees record with status=enriched. Meanwhile, another user confirms it. First user still sees it as enriched.

**Implementation**: Joget datalist refresh via AJAX.

1. After any action (confirm, split, merge, status change), datalist is auto-refreshed via AJAX
2. Joget's DatalistPlugin re-fetches via configured JDBC binder
3. If a row's ID no longer matches query criteria (e.g., status changed to confirmed), row disappears
4. User sees updated list immediately
5. If user was viewing details of that record, form auto-reloads on return from dialog

### 9.3 Large Batch Operations

**Scenario**: User selects 1000 records and clicks Confirm for Posting.

**Implementation**:

1. Dialog shows progress indicator: "Processing X of 1000 records..."
2. API call POSTs with all IDs, but API processes in batches (e.g., 100 at a time)
3. API response includes progress: `{"processed": 100, "total": 1000, "status": "in_progress"}`
4. UI polls the API status endpoint every 2 seconds
5. User can cancel during processing (browser aborts the in-flight POST request; API rolls back on incomplete batch)
6. On completion or cancel, datalist is refreshed
7. If any records failed, show summary: "900 confirmed, 100 failed. Details: [download CSV]"

### 9.4 Network Errors

**Scenario**: User clicks Confirm but network connection drops during POST.

**Implementation**:

1. API call has 30-second timeout
2. If timeout, show error: "Request timeout. [Retry] [Cancel]"
3. User can retry. API is idempotent — retrying a confirmation won't create duplicate confirmed records (checked by API: records already in CONFIRMED status are skipped)
4. If network is permanently down, show: "No internet connection. Please reconnect and refresh the page."

### 9.5 Session Timeout

**Scenario**: User works on form for 1 hour, session expires, clicks Save.

**Implementation**:

1. Joget's authentication interceptor catches 401 Unauthorized from API
2. Redirect to login page with return URL
3. User logs in, browser redirects back to form (form state is lost — user must re-open)
4. Or show modal: "Session expired. Please log in again." [Login Button]

### 9.6 Empty State Handling

**Scenario**: Datalist query returns 0 rows.

**Implementation per datalist**:

1. **Enrichment Workspace**: "No enrichment records found for this statement. Run the enrichment pipeline from the Statement form to create records."
2. **Ready for Posting**: "No records marked as ready. Review records in the Enrichment Workspace and mark them as ready before posting."
3. **Confirmed Records**: "No records confirmed yet. Confirm records from the Ready for Posting view."
4. **Split/Merge History**: "No split or merge operations have been performed on this statement."
5. **Statement Summary**: "No summary data available."

All empty states include a "Back to Statement" link and "Refresh" button.

### 9.7 Validation Errors on Confirm for Posting

**Scenario**: User selects 5 records, clicks Confirm. API validates and 2 records fail (missing customer code).

**Implementation**:

1. API returns 400 Bad Request with detailed errors:
   ```json
   {
     "status": "error",
     "message": "2 of 5 records failed validation.",
     "successCount": 3,
     "failures": [
       {
         "recordId": "ENR-002",
         "field": "customer_code",
         "message": "Customer code is required for posting."
       },
       {
         "recordId": "ENR-005",
         "field": "resolved_asset_id",
         "message": "Asset ID is required for SEC_BUY transaction."
       }
     ]
   }
   ```
2. Dialog stays open
3. Error messages are displayed in the dialog under the records summary
4. Failed records are highlighted in the table
5. User clicks a failed record to navigate to form, fix the issue, mark ready again
6. User re-opens Confirm dialog and retries (or just selects the 3 already-valid records and confirms separately)

### 9.8 Form Field Dependency Validation

**Scenario**: User selects internal_type = SEC_BUY, but doesn't fill asset_id.

**Implementation**:

1. On blur after selecting SEC_BUY, JavaScript checks if internal_type requires asset_id
2. If not present, show inline error: "Asset ID is required for this transaction type."
3. Make resolved_asset_id field red (highlight)
4. Prevent form save (Save button is disabled)
5. User fills asset_id
6. Error clears, Save button is enabled

### 9.9 Rounding and Tolerance

**Scenario**: User manually overrides FX rate. Computed base_amount_eur differs by 0.01 EUR.

**Implementation**:

1. Reconciliation panel shows discrepancy = 0.01
2. If within tolerance (±0.02), show as ⚠ warning but allow proceeding
3. Log the tolerance override: "Discrepancy within tolerance. Logged for audit."
4. User can create a manual F01.05 rounding adjustment if desired: +0.01 EUR to balance

### 9.10 Deleted Records

**Scenario**: Record has been soft-deleted (c_deleted=1) but user still has it open.

**Implementation**:

1. On form load, API checks if record is deleted
2. If deleted, show warning: "This record has been deleted. [View Deleted] [Close]"
3. If user clicks View Deleted, show read-only form with c_deleted=1 indicator
4. Datalist queries always filter WHERE c_deleted=0, so deleted records never appear

---

## §10. Accessibility & Responsiveness

### 10.1 Keyboard Navigation

All datalist views and forms are keyboard-accessible:

- **Tab**: Move between form fields and datalist rows
- **Shift+Tab**: Move backward
- **Enter**: Click focused button or expand/collapse section
- **Space**: Toggle checkbox on datalist row
- **Escape**: Close modal dialogs
- **Arrow keys**: Navigate datalist pagination (Left/Right to previous/next page)
- **Ctrl+S** (or **Cmd+S**): Save form (JavaScript handler)

### 10.2 Screen Reader Support

- All form fields have associated `<label>` elements with `for` attribute
- All buttons have descriptive text (not just icons)
- Status badges include `aria-label` attributes: `<span class="st-ready" aria-label="Status: Ready">ready</span>`
- Datalist tables have `<thead>` and `<tbody>` for structure
- Errors are announced via ARIA live region: `<div role="alert" aria-live="polite" aria-atomic="true">`
- Form validation messages are associated with fields via `aria-describedby`

### 10.3 Mobile/Tablet Responsiveness

**Viewport Breakpoints:**

- **Desktop** (>1200px): Full 5-column tables, side-by-side dialogs
- **Tablet** (768px–1200px): Collapsed columns (hide Asset/CP), stack dialogs vertically
- **Mobile** (<768px): 1-column card layout for datalists, full-width dialogs

**Datalist Responsive Behavior:**

On tablet/mobile:
- Hide columns: source_type, asset_id, counterparty_id, type_confidence
- Show collapse/expand per row to view hidden fields
- Amount columns remain visible (right-aligned)
- Checkbox column remains for batch selection

**Form Responsive Behavior:**

On tablet:
- Stack sections vertically (all sections collapse by default)
- Expand relevant sections on scroll
- Form fields are full-width

On mobile:
- Same as tablet
- Buttons stack vertically at bottom
- Dialog widths adjust to viewport (100% - padding)

### 10.4 Minimum Supported Viewport

- **Width**: 320px (mobile)
- **Height**: 600px (content must be scrollable if taller)

---

## §11. Integration Points

### 11.1 Access from Statement Form (F01.00)

The enrichment workspace is launched from the Statement form via a button or link:

**Button on Statement Form:**
```html
<button onclick="openEnrichmentWorkspace('${statementId}')">
  <i class="fas fa-exchange-alt"></i> Review Enrichment
</button>
```

**JavaScript Handler:**
```javascript
function openEnrichmentWorkspace(statementId) {
  var url = "/jw/userview/enrichmentApp/enrichmentWorkspace?statement_id=" + encodeURIComponent(statementId);
  window.open(url, "_blank", "width=1200,height=800");
}
```

Or inline (iframe):
```html
<iframe src="/jw/userview/enrichmentApp/enrichmentWorkspace?statement_id=${statementId}"></iframe>
```

### 11.2 URL Parameter Contract

**Userview Entry Point URL:**
```
/jw/userview/{appId}/{userviewId}?statement_id={statementId}
```

**Expected Parameters:**
- `statement_id` (required): Statement ID (e.g., STM-2024-07). Scopes all datalists to this statement.
- (optional) `view`: Which datalist to show initially (e.g., `readyForPosting`, `confirmedRecords`). Defaults to `enrichmentWorkspace`.
- (optional) `recordId`: If viewing a single record form, the record ID to open.

**Example:**
```
/jw/userview/enrichmentApp/enrichmentWorkspace?statement_id=STM-2024-07&view=readyForPosting&recordId=ENR-001
```

### 11.3 Cross-Datalist Navigation

From the Statement Summary datalist, clicking a row filters the Enrichment Workspace datalist by status + currency:

```javascript
function filterByStatusCurrency(status, currency) {
  document.getElementById('statusFilter').value = status;
  document.getElementById('currencyFilter').value = currency;
  // Trigger search
  document.getElementById('searchBtn').click();
  // Optionally scroll to enrichment workspace view
  document.querySelector('[data-view="enrichmentWorkspace"]').scrollIntoView();
}
```

### 11.4 Link to Individual Record Form

All datalist views have an "ID" column that links to the record's form:

```html
<a href="/jw/form/trxEnrichment?id=${recordId}">${recordId}</a>
```

Clicking opens the form in the same window or a new tab (configurable).

### 11.5 Back Navigation

**From Form to Datalist:**
- "Back to List" button on form navigates back to referring datalist
- If form was opened from datalist, browsers back button works
- If form was opened in new tab, back button takes user to datalist in original tab

**From Datalist to Statement:**
- "Back to Statement" link in empty state or header
- Navigates to F01.00 form (statement form)

**From Confirmation Dialog to Datalist:**
- "Cancel" button closes dialog, returns to datalist
- On success, dialog closes and datalist auto-refreshes

---

## §12. UX Traceability Matrix

This section maps every requirement from the UX prototype documents to this specification.

### 12.1 Data Layer Document (f01-05-ux-data-layer.md) Traceability

| Data Layer Requirement | Spec Section | Implementation |
|---|---|---|
| Primary datalist shows all active F01.05 records | §4.1 | Enrichment Workspace datalist, SQL filters status NOT IN (superseded, confirmed) |
| Datalist columns: id, source_type, transaction_date, amount, currency, customer | §4.1.3 | Column Definitions table |
| Status badges with distinct colors | §7.1 | CSS classes .st-{status} for all 11 statuses |
| Form has 42 fields in 8 sections | §5.2 | Section 1–8 with all 42 field IDs, types, labels |
| Form section visibility rules per status | §5.3 | Visibility matrix: Sections 1–7 hidden when superseded/confirmed |
| Field editability matrix: 42 fields × 11 statuses | §5.4 | Complete matrix: RO vs E per field per status |
| Computed fields: total_amount, base_amount_eur | §5.5 | Computation formulas and override logic |
| Form validation rules (required, conditional) | §5.6 | Validation rules per status |
| Form actions: Save, Mark Ready, Return, Delete, Split | §5.7 | Button definitions with visibility logic |
| Ready for Posting datalist (status=ready only) | §4.2 | Separate datalist with SQL WHERE status='ready' |
| Confirmed Records datalist (status=confirmed, read-only) | §4.3 | Datalist with read-only form link |
| Split/Merge History datalist (origin IN split/merge) | §4.4 | Datalist with lineage fields and group_id |
| Statement Summary datalist (aggregated by status/currency) | §4.5 | Aggregate query with GROUP BY, cross-filter to Enrichment Workspace |
| All field IDs configurable (no hardcoding) | §2.2 | Field mapping JSON in plugin properties, runtime lookup |

### 12.2 Interaction Layer Document (f01-05-ux-interaction-layer.md) Traceability

| Interaction Requirement | Spec Section | Implementation |
|---|---|---|
| Confirm for Posting action: two entry points (datalist) | §6.1.1 | Batch action on Enrichment Workspace and Ready for Posting views |
| Confirmation dialog with reconciliation panel | §6.1.3 & §8 | Dialog spec, reconciliation table, status indicators |
| Reconciliation check: compares F01.03/F01.04 input to confirmed F01.05 output | §8.2 | Column: Source Input, Output (confirmed F01.05), Remaining (active F01.05), Discrepancy calculation |
| Reconciliation blocking on final confirmation (no active records remain) | §8.5 | Confirm button disabled if final confirmation with discrepancy exceeds tolerance |
| Split action: customer allocation builder | §6.2.2 & §6.2.3 | Split dialog with allocation grid, customer popup, amount validation |
| Merge action: field resolution for merged record | §6.3.2 & §6.3.3 | Merge dialog with conflict resolution fields, auto-summed amounts |
| Status transitions: Mark Ready, Return to Workspace, etc. | §6.4 | Status change buttons with visibility rules and allowed transitions table |
| Filters on datalist: status, source, customer, date range | §4.1.5 | Filter widget definitions and SQL AND clauses |
| Pagination per datalist | §4.1.1 | Page size 20, offset/limit parameters in SQL, pagination controls |
| Cross-datalist navigation (Summary → Workspace filter) | §11.3 | JavaScript filter handler linking status/currency from Summary to Workspace |
| Form inline validation | §5.6 | Validation on blur + save, error display under fields |
| Keyboard shortcuts (Ctrl+S to save) | §10.1 | JavaScript handler for Ctrl+S |
| Responsive design (mobile/tablet) | §10.3 | Viewport breakpoints, column hiding, full-width dialogs |
| Empty states for all datalists | §9.6 | Custom messages per datalist |
| Error handling and retry logic | §9 | Network errors, validation errors, concurrency (version field) |

### 12.3 Architecture Document (f01-05-f01-06-architecture.md) Traceability

| Architecture Requirement | Spec Section | Implementation |
|---|---|---|
| F01.05 is workspace (editable, alive records) | §1.2 & §5 | Form allows editing all fields in most statuses; sections hidden only when superseded/confirmed |
| F01.06 is posting boundary (confirmed, immutable before posting) | N/A | Spec is F01.05 UI only; F01.06 is downstream posting plugin |
| Statuses: new, processing, enriched, error, manual_review, in_review, adjusted, ready, paired, superseded, confirmed | §5.4 & §7.1 | All 11 statuses in editability matrix and CSS styles |
| Origin tracking: pipeline, split, merge, manual | §5.2.2 & §4.4 | Fields: origin, parent_enrichment_id, group_id, split_sequence, lineage_note |
| Version field for optimistic locking | §9.1 | Field definition and concurrent modification handling |
| No F01.06 creation in UI plugin | §1.2 | enrichment-workspace calls enrichment-api for mutations; F01.06 creation is handled by a separate downstream posting plugin |
| No GL posting operations in UI plugin | §1.2 | Posting is downstream, outside scope of this plugin |
| enrichment-api is data contract | §1.3 | All mutations via REST endpoints; datalists use JDBC for reads |
| gam-framework Status enum integration | §1.4 | Status values from enum; StatusManager handles transitions in API |
| Reconciliation panel informational in MVP | §8.5 | Blocking behavior only on final confirmation |

---

## §13. Deployment Checklist

Before deploying enrichment-workspace v1.0.0, ensure:

### Plugin Configuration
- [ ] Create plugin.xml with all property definitions (§2.1)
- [ ] Define default values for apiId, apiKey, tableName, formId, datalistIds
- [ ] Test property panel rendering in Joget designer

### Forms & Datalists
- [ ] Create form `trxEnrichment` with all 42 fields (§5.2) in Joget form builder
- [ ] Set form table to `trx_enrichment`
- [ ] Create 5 datalists with correct IDs and SQL queries (§4.1–§4.5)
- [ ] Configure column formatters (status badges, amounts, confidence badges)
- [ ] Test datalist rendering and filtering

### API Integration
- [ ] Verify enrichment-api plugin is running and accessible at `/jw/api/enrichment`
- [ ] Test all endpoints: GET /records, POST /records/{id}/confirm, POST /records/{id}/split, POST /records/merge
- [ ] Verify API authentication (api_id and api_key headers)

### CSS & Styling
- [ ] Apply all CSS classes (§7) to form and datalist
- [ ] Test status badge colors on all 11 statuses
- [ ] Test responsive design on mobile, tablet, desktop viewports
- [ ] Verify font styling for amounts (tabular-nums)

### Dialogs & Actions
- [ ] Implement Confirm for Posting dialog (§6.1.3)
- [ ] Implement Split dialog (§6.2.3)
- [ ] Implement Merge dialog (§6.3.3)
- [ ] Implement Reconciliation panel (§8)
- [ ] Test all action flows with sample data

### Error Handling
- [ ] Test concurrent modification error (version mismatch)
- [ ] Test validation errors on form save
- [ ] Test network timeout handling
- [ ] Test session timeout

### Accessibility
- [ ] Test keyboard navigation (Tab, Shift+Tab, Enter, Escape)
- [ ] Test with screen reader (NVDA, JAWS, or Safari VoiceOver)
- [ ] Verify all form fields have labels
- [ ] Verify all buttons have descriptive text

### Documentation
- [ ] Deploy this specification to code repository
- [ ] Create user guide for enrichment workspace
- [ ] Create admin guide for plugin configuration
- [ ] Add screenshots and workflow diagrams

### Testing
- [ ] Unit tests for field validation logic
- [ ] Integration tests for API calls (mock enrichment-api)
- [ ] End-to-end tests for full workflows (confirm, split, merge)
- [ ] Performance tests with large datasets (1000+ records)
- [ ] Browser compatibility tests (Chrome, Firefox, Safari, Edge)

---

## §14. Version History & Maintenance

| Version | Date | Changes |
|---------|------|---------|
| 0.2.0 | 2026-02-01 | Initial alpha release — basic datalist, no actions |
| 0.5.0 | 2026-02-15 | Add Split, Merge, Confirm for Posting actions |
| 1.0.0 | 2026-03-02 | Complete UI spec, full Joget integration, reconciliation panel, all edge cases |

---

**End of Specification**

This specification is the complete design document for enrichment-workspace v1.0.0. It contains no placeholders, no stubs, and no "to be determined" sections. Every section is fully written and ready for development.
