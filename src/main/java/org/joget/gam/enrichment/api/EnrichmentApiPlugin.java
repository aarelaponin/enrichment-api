package org.joget.gam.enrichment.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import com.fiscaladmin.gam.framework.status.EntityType;
import com.fiscaladmin.gam.framework.status.InvalidTransitionException;
import com.fiscaladmin.gam.framework.status.Status;
import com.fiscaladmin.gam.framework.status.StatusManager;
import org.joget.api.annotations.Operation;
import org.joget.api.annotations.Param;
import org.joget.api.annotations.Response;
import org.joget.api.annotations.Responses;
import org.joget.api.model.ApiPluginAbstract;
import org.joget.api.model.ApiResponse;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;
import org.joget.gam.enrichment.service.DeleteNotAllowedException;
import org.joget.gam.enrichment.service.EnrichmentService;
import org.joget.gam.enrichment.service.RecordNotFoundException;
import org.joget.gam.enrichment.service.TerminalStatusException;
import org.joget.gam.enrichment.service.ValidationConfig;
import org.joget.gam.enrichment.service.VersionConflictException;
import org.joget.plugin.property.model.PropertyEditable;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Enrichment API — REST endpoints for the enrichment workspace.
 *
 * <p>Form-agnostic: formDefId and tableName are configured via plugin properties
 * (selected in API Builder UI). FormRow keys are iterated dynamically — no
 * hardcoded field map. JSON keys use form element IDs directly, matching Joget
 * native patterns (FormUtil.formRowSetToJson, AppFormAPI).</p>
 */
public class EnrichmentApiPlugin extends ApiPluginAbstract implements PropertyEditable {

    private static final String CLASS_NAME = "org.joget.gam.enrichment.api.EnrichmentApiPlugin";
    private static final Pattern SAFE_FIELD_NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final EnrichmentService SERVICE = new EnrichmentService();
    private static final StatusManager STATUS_MANAGER = new StatusManager();

    /** Fields stored as strings but representing numeric values — need Java-side sort. */
    private static final Set<String> NUMERIC_SORT_FIELDS = Set.of(
            "original_amount", "fee_amount", "total_amount");

    // ValidationConfig cache (M4)
    private int cachedConfigHash;
    private ValidationConfig cachedConfig;

    // ── Plugin identity ────────────────────────────────────────────────────

    @Override
    public String getName() {
        return "enrichment-api";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "REST API for enrichment workspace operations";
    }

    @Override
    public String getLabel() {
        return "Enrichment API";
    }

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    // ── API Builder integration ────────────────────────────────────────────

    @Override
    public String getIcon() {
        return "<i class=\"fas fa-exchange-alt\"></i>";
    }

    @Override
    public String getTag() {
        return "enrichment";
    }

    @Override
    public String getTagDesc() {
        return "Enrichment workspace operations — records, actions, reconciliation";
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(
                getClass().getName(),
                "/properties/EnrichmentApiPlugin.json",
                null,
                true,
                null
        );
    }

    // ── Configuration helpers ──────────────────────────────────────────────

    private String getTableName() {
        String table = getPropertyString("tableName");
        if (table == null || table.trim().isEmpty()) {
            throw new IllegalStateException("tableName plugin property is not configured");
        }
        return table.trim();
    }

    private String getDefaultSortField() {
        String sort = getPropertyString("defaultSort");
        if (sort == null || sort.trim().isEmpty()) {
            return "dateCreated";
        }
        return sort.trim();
    }

    /** Returns the configured max page size, clamped to 1-1000. Default 200. */
    private int getMaxPageSize() {
        String val = getPropertyString("maxPageSize");
        if (val == null || val.trim().isEmpty()) {
            return 200;
        }
        try {
            int n = Integer.parseInt(val.trim());
            return Math.max(1, Math.min(n, 1000));
        } catch (NumberFormatException e) {
            return 200;
        }
    }

    /** Returns true if batch operations (split, merge, confirm) are enabled. */
    private boolean isBatchOperationsEnabled() {
        String val = getPropertyString("enableBatchOperations");
        // Joget checkbox returns the value string if checked, empty if not
        return val != null && !val.trim().isEmpty();
    }

    /**
     * Returns the cached ValidationConfig, re-parsing only if the property string changed.
     */
    private synchronized ValidationConfig getValidationConfig() {
        String configStr = getPropertyString("validationConfig");
        int hash = configStr != null ? configStr.hashCode() : 0;
        if (cachedConfig == null || hash != cachedConfigHash) {
            cachedConfig = ValidationConfig.parse(configStr);
            cachedConfigHash = hash;
            LogUtil.info(CLASS_NAME, "ValidationConfig parsed (hash=" + hash + ")");
        }
        return cachedConfig;
    }

    // ── Phase 0: Health check ──────────────────────────────────────────────

    @Operation(
            path = "/health",
            type = Operation.MethodType.GET,
            summary = "Health check",
            description = "Returns OK if the enrichment API plugin is loaded and responding."
    )
    @Responses({
            @Response(responseCode = 200, description = "Plugin is healthy")
    })
    public ApiResponse health() {
        // Check validationConfig
        try {
            String configStr = getPropertyString("validationConfig");
            if (configStr == null || configStr.trim().isEmpty()) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("status", "error");
                err.put("message", "validationConfig property is not set or invalid JSON");
                return new ApiResponse(500, err);
            }
            new JSONObject(configStr); // parse check
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "error");
            err.put("message", "validationConfig property is not set or invalid JSON");
            return new ApiResponse(500, err);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("plugin", getName());
        result.put("version", getVersion());
        result.put("tableName", getTableName());
        result.put("timestamp", Instant.now().toString());
        result.put("uptime_ms", System.currentTimeMillis() - Activator.getStartTime());
        return new ApiResponse(200, result);
    }

    // ── Phase 1: Records listing ───────────────────────────────────────────

    @Operation(
            path = "/records",
            type = Operation.MethodType.GET,
            summary = "List enrichment records",
            description = "Returns paginated records from the configured form table."
    )
    @Responses({
            @Response(responseCode = 200, description = "Records returned successfully"),
            @Response(responseCode = 400, description = "Invalid parameters"),
            @Response(responseCode = 500, description = "Server error")
    })
    public ApiResponse records(
            @Param(value = "filter", required = false,
                    description = "Filter as field=value pairs, comma-separated") String filter,
            @Param(value = "search", required = false,
                    description = "Substring search on a field, format: field:value") String search,
            @Param(value = "page", required = false,
                    description = "Page number, 1-based (default 1)") Integer page,
            @Param(value = "pageSize", required = false,
                    description = "Records per page (default 20, max: configured maxPageSize)") Integer pageSize,
            @Param(value = "sort", required = false,
                    description = "Sort field — form element ID") String sort,
            @Param(value = "order", required = false,
                    description = "Sort order: asc or desc (default: asc)") String order,
            @Param(value = "save", required = false,
                    description = "JSON body for inline save or batch action — dispatched by content") String save
    ) {
        // Dispatch to save/action handler if 'save' parameter is present
        if (save != null && !save.trim().isEmpty()) {
            return dispatchSaveParam(save);
        }

        long t0 = System.currentTimeMillis();
        String tableName = getTableName();
        int maxPageSize = getMaxPageSize();

        try {
            int pg = (page != null && page > 0) ? page : 1;
            int ps = (pageSize != null && pageSize > 0) ? Math.min(pageSize, maxPageSize) : 20;
            Boolean sortDesc = "desc".equalsIgnoreCase(order) ? Boolean.TRUE : Boolean.FALSE;
            String sortField = (sort != null && !sort.trim().isEmpty()) ? sort.trim() : getDefaultSortField();

            if ("created".equals(sortField)) sortField = "dateCreated";
            else if ("modified".equals(sortField)) sortField = "dateModified";

            if (!isStandardField(sortField) && !SAFE_FIELD_NAME.matcher(sortField).matches()) {
                return invalidParams("Invalid sort field name: " + sortField, t0);
            }

            StringBuilder cond = new StringBuilder();
            List<Object> params = new ArrayList<>();

            if (filter != null && !filter.trim().isEmpty()) {
                String[] pairs = filter.split(",");
                for (String pair : pairs) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2 && !kv[0].trim().isEmpty() && !kv[1].trim().isEmpty()) {
                        String fieldName = kv[0].trim();
                        if (!SAFE_FIELD_NAME.matcher(fieldName).matches()) {
                            return invalidParams("Invalid filter field name: " + fieldName, t0);
                        }
                        cond.append(cond.length() == 0 ? "WHERE " : " AND ");
                        cond.append("e.customProperties.").append(fieldName).append(" = ?");
                        params.add(kv[1].trim());
                    }
                }
            }

            if (search != null && !search.trim().isEmpty()) {
                String[] kv = search.split(":", 2);
                if (kv.length == 2 && !kv[0].trim().isEmpty() && !kv[1].trim().isEmpty()) {
                    String fieldName = kv[0].trim();
                    if (!SAFE_FIELD_NAME.matcher(fieldName).matches()) {
                        return invalidParams("Invalid search field name: " + fieldName, t0);
                    }
                    cond.append(cond.length() == 0 ? "WHERE " : " AND ");
                    cond.append("e.customProperties.").append(fieldName).append(" LIKE ?");
                    params.add("%" + kv[1].trim() + "%");
                }
            }

            String condition = cond.length() > 0 ? cond.toString() : "";
            Object[] paramsArr = params.isEmpty() ? new Object[0] : params.toArray();

            FormDataDao dao = (FormDataDao) AppUtil.getApplicationContext().getBean("formDataDao");

            int start = (pg - 1) * ps;
            long total = dao.count(null, tableName, condition, paramsArr);

            List<Map<String, Object>> records = new ArrayList<>();

            if (NUMERIC_SORT_FIELDS.contains(sortField)) {
                // Joget stores all values as VARCHAR — numeric fields sort lexicographically.
                // Fetch all matching rows, sort numerically in Java, then paginate.
                FormRowSet allRows = dao.find(null, tableName, condition, paramsArr,
                        "dateCreated", Boolean.FALSE, 0, (int) total);
                List<Map<String, Object>> all = new ArrayList<>();
                if (allRows != null) {
                    for (Object obj : allRows) {
                        if (obj instanceof FormRow) {
                            all.add(rowToMap((FormRow) obj));
                        }
                    }
                }
                final String sf = sortField;
                final boolean isDesc = Boolean.TRUE.equals(sortDesc);
                all.sort((a, b) -> {
                    double va = parseDouble(a.get(sf));
                    double vb = parseDouble(b.get(sf));
                    return isDesc ? Double.compare(vb, va) : Double.compare(va, vb);
                });
                int from = Math.min(start, all.size());
                int to = Math.min(start + ps, all.size());
                records = new ArrayList<>(all.subList(from, to));
            } else {
                FormRowSet rows = dao.find(null, tableName, condition, paramsArr,
                        sortField, sortDesc, start, ps);
                if (rows != null) {
                    for (Object obj : rows) {
                        if (obj instanceof FormRow) {
                            records.add(rowToMap((FormRow) obj));
                        }
                    }
                }
            }

            int totalPages = ps > 0 ? (int) Math.ceil((double) total / ps) : 0;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("records", records);
            result.put("page", pg);
            result.put("pageSize", ps);
            result.put("total", total);
            result.put("totalPages", totalPages);
            result.put("sort", sortField);
            result.put("order", sortDesc ? "desc" : "asc");
            result.put("ms", System.currentTimeMillis() - t0);

            return new ApiResponse(200, result);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error fetching records from table: " + tableName);
            return databaseError("Error fetching records from table: " + tableName, t0);
        }
    }

    // ── Phase 2: Single record ────────────────────────────────────────────

    @Operation(
            path = "/records/{id}",
            type = Operation.MethodType.GET,
            summary = "Get a single enrichment record",
            description = "Returns a single record by ID from the configured form table."
    )
    @Responses({
            @Response(responseCode = 200, description = "Record returned successfully"),
            @Response(responseCode = 404, description = "Record not found"),
            @Response(responseCode = 500, description = "Server error")
    })
    public ApiResponse getRecord(
            @Param(value = "id", description = "Record primary key") String id
    ) {
        long t0 = System.currentTimeMillis();
        String tableName = getTableName();

        try {
            FormRow row = SERVICE.loadRecord(tableName, id);
            if (row == null) {
                return notFound("Record not found: " + id, t0);
            }

            Map<String, Object> result = rowToMap(row);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error loading record: " + id);
            return databaseError("Error loading record: " + id, t0);
        }
    }

    @Operation(
            path = "/records/{id}",
            type = Operation.MethodType.PUT,
            summary = "Update an enrichment record",
            description = "Inline-edit fields on a single record. Requires optimistic lock version."
    )
    @Responses({
            @Response(responseCode = 200, description = "Record updated successfully"),
            @Response(responseCode = 400, description = "Invalid request or terminal status"),
            @Response(responseCode = 404, description = "Record not found"),
            @Response(responseCode = 409, description = "Version conflict"),
            @Response(responseCode = 500, description = "Server error")
    })
    public ApiResponse updateRecord(
            @Param(value = "id", description = "Record primary key") String id,
            @Param(value = "body", description = "JSON body with version and fields to update") String requestBody
    ) {
        long t0 = System.currentTimeMillis();
        String tableName = getTableName();

        try {
            JSONObject body = new JSONObject(requestBody);

            if (!body.has("version")) {
                return validationError("Missing required field: version", t0);
            }
            int expectedVersion = body.getInt("version");

            Map<String, String> fieldsToUpdate = new HashMap<>();
            Iterator<String> keys = body.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("id".equals(key) || "version".equals(key)) {
                    continue;
                }
                fieldsToUpdate.put(key, body.optString(key, ""));
            }

            ValidationConfig config = getValidationConfig();
            FormRow updated = SERVICE.updateRecord(
                    tableName, id, fieldsToUpdate, expectedVersion,
                    config.getConfidenceOverrides());

            Map<String, Object> result = rowToMap(updated);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (RecordNotFoundException e) {
            return notFound(e.getMessage(), t0);

        } catch (VersionConflictException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "VERSION_CONFLICT");
            err.put("message", e.getMessage());
            err.put("currentVersion", e.getCurrentVersion());
            err.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(409, err);

        } catch (TerminalStatusException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "TERMINAL_STATUS");
            err.put("message", e.getMessage());
            err.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(400, err);

        } catch (InvalidTransitionException e) {
            Set<Status> valid = STATUS_MANAGER.getValidTransitions(
                    EntityType.ENRICHMENT, e.getFromStatus());
            List<String> validCodes = new ArrayList<>();
            for (Status s : valid) {
                validCodes.add(s.getCode());
            }

            Map<String, Object> err = new HashMap<>();
            err.put("error", "INVALID_TRANSITION");
            err.put("message", e.getMessage());
            err.put("currentStatus", e.getFromStatus() != null ? e.getFromStatus().getCode() : null);
            err.put("targetStatus", e.getToStatus() != null ? e.getToStatus().getCode() : null);
            err.put("validTransitions", validCodes);
            err.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(422, err);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error updating record: " + id);
            return databaseError("Error updating record: " + id, t0);
        }
    }

    // ── Phase 3: Single status transition ──────────────────────────────────

    @Operation(
            path = "/records/{id}/status",
            type = Operation.MethodType.POST,
            summary = "Transition record status",
            description = "Transitions a single record's status via StatusManager."
    )
    @Responses({
            @Response(responseCode = 200, description = "Status transitioned successfully"),
            @Response(responseCode = 400, description = "Invalid transition or bad request"),
            @Response(responseCode = 404, description = "Record not found"),
            @Response(responseCode = 500, description = "Server error")
    })
    public ApiResponse transitionStatus(
            @Param(value = "id", description = "Record primary key") String id,
            @Param(value = "body", description = "JSON body with targetStatus and optional reason") String requestBody
    ) {
        long t0 = System.currentTimeMillis();
        String tableName = getTableName();

        try {
            JSONObject body = new JSONObject(requestBody);

            String targetStatusCode = body.optString("targetStatus", "");
            if (targetStatusCode.isEmpty()) {
                return validationError("Missing required field: targetStatus", t0);
            }

            Status targetStatus;
            try {
                targetStatus = Status.fromCode(targetStatusCode);
            } catch (IllegalArgumentException e) {
                return validationError("Unknown status code: " + targetStatusCode, t0);
            }

            String reason = body.optString("reason", "Status transition via API");

            Map<String, Object> result = SERVICE.transitionStatus(tableName, id, targetStatus, reason);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (RecordNotFoundException e) {
            return notFound(e.getMessage(), t0);

        } catch (InvalidTransitionException e) {
            Set<Status> valid = STATUS_MANAGER.getValidTransitions(
                    EntityType.ENRICHMENT, e.getFromStatus());
            List<String> validCodes = new ArrayList<>();
            for (Status s : valid) {
                validCodes.add(s.getCode());
            }

            Map<String, Object> err = new HashMap<>();
            err.put("error", "INVALID_TRANSITION");
            err.put("message", e.getMessage());
            err.put("currentStatus", e.getFromStatus() != null ? e.getFromStatus().getCode() : null);
            err.put("targetStatus", e.getToStatus() != null ? e.getToStatus().getCode() : null);
            err.put("validTransitions", validCodes);
            err.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(422, err);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error transitioning status for record: " + id);
            return databaseError("Error transitioning status for record: " + id, t0);
        }
    }

    // ── Phase 3: Batch status transition ───────────────────────────────────

    @Operation(
            path = "/records/status",
            type = Operation.MethodType.POST,
            summary = "Batch status transition",
            description = "Applies the same status transition to multiple records."
    )
    @Responses({
            @Response(responseCode = 200, description = "Batch transition completed"),
            @Response(responseCode = 400, description = "Invalid request"),
            @Response(responseCode = 500, description = "Server error")
    })
    public ApiResponse batchTransitionStatus(
            @Param(value = "body", description = "JSON body with recordIds, targetStatus, and optional reason") String requestBody
    ) {
        long t0 = System.currentTimeMillis();
        String tableName = getTableName();

        try {
            JSONObject body = new JSONObject(requestBody);

            JSONArray idsArr = body.optJSONArray("recordIds");
            if (idsArr == null || idsArr.length() == 0) {
                return validationError("Missing or empty recordIds array", t0);
            }

            String targetStatusCode = body.optString("targetStatus", "");
            if (targetStatusCode.isEmpty()) {
                return validationError("Missing required field: targetStatus", t0);
            }

            Status targetStatus;
            try {
                targetStatus = Status.fromCode(targetStatusCode);
            } catch (IllegalArgumentException e) {
                return validationError("Unknown status code: " + targetStatusCode, t0);
            }

            String reason = body.optString("reason", "Batch status transition via API");

            List<String> recordIds = new ArrayList<>();
            for (int i = 0; i < idsArr.length(); i++) {
                recordIds.add(idsArr.getString(i));
            }

            Map<String, Object> result = SERVICE.batchTransitionStatus(
                    tableName, recordIds, targetStatus, reason);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error in batch status transition");
            return databaseError("Batch transition failed", t0);
        }
    }

    // ── Phase 3: Delete record ─────────────────────────────────────────────

    @Operation(
            path = "/records/{id}",
            type = Operation.MethodType.DELETE,
            summary = "Delete an enrichment record",
            description = "Deletes a record. Only allowed for status: new, error, manual_review."
    )
    @Responses({
            @Response(responseCode = 200, description = "Record deleted"),
            @Response(responseCode = 400, description = "Deletion not allowed for current status"),
            @Response(responseCode = 404, description = "Record not found"),
            @Response(responseCode = 500, description = "Server error")
    })
    public ApiResponse deleteRecord(
            @Param(value = "id", description = "Record primary key") String id
    ) {
        long t0 = System.currentTimeMillis();
        String tableName = getTableName();

        try {
            SERVICE.deleteRecord(tableName, id);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("message", "Record deleted successfully");
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (RecordNotFoundException e) {
            return notFound(e.getMessage(), t0);

        } catch (DeleteNotAllowedException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "DELETE_NOT_ALLOWED");
            err.put("message", e.getMessage());
            err.put("currentStatus", e.getCurrentStatus());
            err.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(400, err);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error deleting record: " + id);
            return databaseError("Error deleting record: " + id, t0);
        }
    }

    // ── Phase 4: Summary ───────────────────────────────────────────────────

    @Operation(
            path = "/summary",
            type = Operation.MethodType.GET,
            summary = "Get per-statement summary counts",
            description = "Returns record counts grouped by statement and status."
    )
    @Responses({
            @Response(responseCode = 200, description = "Summary returned successfully"),
            @Response(responseCode = 500, description = "Server error")
    })
    public ApiResponse summary() {
        long t0 = System.currentTimeMillis();
        String tableName = getTableName();

        try {
            ValidationConfig config = getValidationConfig();
            List<Map<String, Object>> statements = SERVICE.getSummary(tableName, config);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("statements", statements);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error getting summary");
            return databaseError("Error computing summary", t0);
        }
    }

    // ── Phase 5: Reconciliation ────────────────────────────────────────────

    @Operation(
            path = "/reconciliation/{statementId}",
            type = Operation.MethodType.GET,
            summary = "Get reconciliation for a statement",
            description = "Computes per-currency reconciliation for a statement."
    )
    @Responses({
            @Response(responseCode = 200, description = "Reconciliation computed successfully"),
            @Response(responseCode = 400, description = "Reconciliation config missing"),
            @Response(responseCode = 500, description = "Server error")
    })
    public ApiResponse reconciliation(
            @Param(value = "statementId", description = "Statement ID") String statementId
    ) {
        long t0 = System.currentTimeMillis();
        String tableName = getTableName();

        try {
            ValidationConfig config = getValidationConfig();
            if (config.getReconciliation() == null) {
                return configError("Reconciliation config is not set", t0);
            }

            Map<String, Object> result = SERVICE.getReconciliation(tableName, statementId, config);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error computing reconciliation for: " + statementId);
            return databaseError("Error computing reconciliation", t0);
        }
    }

    // ── Phase 6: Confirm ───────────────────────────────────────────────────

    @Operation(
            path = "/records/confirm",
            type = Operation.MethodType.POST,
            summary = "Confirm records for posting",
            description = "Validates and transitions selected records to CONFIRMED status."
    )
    @Responses({
            @Response(responseCode = 200, description = "Confirmation completed"),
            @Response(responseCode = 400, description = "Batch operations disabled or no valid records"),
            @Response(responseCode = 500, description = "Server error")
    })
    public ApiResponse confirmRecords(
            @Param(value = "body", description = "JSON body with recordIds and allowPartial flag") String requestBody
    ) {
        long t0 = System.currentTimeMillis();
        String tableName = getTableName();

        try {
            if (!isBatchOperationsEnabled()) {
                return configError("Batch operations are disabled. Enable 'enableBatchOperations' in plugin properties.", t0);
            }

            JSONObject body = new JSONObject(requestBody);

            JSONArray idsArr = body.optJSONArray("recordIds");
            if (idsArr == null || idsArr.length() == 0) {
                return validationError("Missing or empty recordIds array", t0);
            }

            boolean allowPartial = body.optBoolean("allowPartial", false);

            List<String> recordIds = new ArrayList<>();
            for (int i = 0; i < idsArr.length(); i++) {
                recordIds.add(idsArr.getString(i));
            }

            ValidationConfig config = getValidationConfig();
            Map<String, Object> result = SERVICE.confirmRecords(
                    tableName, recordIds, allowPartial, config);

            // If confirmed:0 with validation errors, return 400
            Object confirmedCount = result.get("confirmed");
            List<?> valErrors = (List<?>) result.get("validationErrors");
            if (confirmedCount != null && ((Number) confirmedCount).intValue() == 0
                    && valErrors != null && !valErrors.isEmpty()) {
                result.put("error", "VALIDATION_FAILED");
                result.put("message", "Confirmation failed: validation errors");
                result.put("ms", System.currentTimeMillis() - t0);
                return new ApiResponse(400, result);
            }

            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error confirming records");
            return databaseError("Confirmation failed: " + e.getMessage(), t0);
        }
    }

    // ── Phase 7: Split ─────────────────────────────────────────────────────

    @Operation(
            path = "/records/{id}/split",
            type = Operation.MethodType.POST,
            summary = "Split a record into multiple allocations",
            description = "Splits a parent record into N child records with allocated amounts."
    )
    @Responses({
            @Response(responseCode = 200, description = "Split completed successfully"),
            @Response(responseCode = 400, description = "Validation failure or batch ops disabled"),
            @Response(responseCode = 404, description = "Parent record not found"),
            @Response(responseCode = 500, description = "Server error")
    })
    public ApiResponse splitRecord(
            @Param(value = "id", description = "Parent record primary key") String id,
            @Param(value = "body", description = "JSON body with allocations array") String requestBody
    ) {
        long t0 = System.currentTimeMillis();
        String tableName = getTableName();

        try {
            if (!isBatchOperationsEnabled()) {
                return configError("Batch operations are disabled. Enable 'enableBatchOperations' in plugin properties.", t0);
            }

            JSONObject body = new JSONObject(requestBody);
            JSONArray allocArr = body.optJSONArray("allocations");
            if (allocArr == null || allocArr.length() == 0) {
                return validationError("Missing or empty allocations array", t0);
            }

            List<Map<String, String>> allocations = new ArrayList<>();
            for (int i = 0; i < allocArr.length(); i++) {
                JSONObject allocObj = allocArr.getJSONObject(i);
                Map<String, String> alloc = new HashMap<>();
                Iterator<String> keys = allocObj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    alloc.put(key, allocObj.optString(key, ""));
                }
                allocations.add(alloc);
            }

            ValidationConfig config = getValidationConfig();
            Map<String, Object> result = SERVICE.splitRecord(tableName, id, allocations, config);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (RecordNotFoundException e) {
            return notFound(e.getMessage(), t0);

        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("does not allow split")) {
                return invalidStatus(msg, t0);
            }
            return validationFailed(msg, t0);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error splitting record: " + id);
            return databaseError("Split failed: " + e.getMessage(), t0);
        }
    }

    // ── Phase 7: Merge ─────────────────────────────────────────────────────

    @Operation(
            path = "/records/merge",
            type = Operation.MethodType.POST,
            summary = "Merge multiple records into one",
            description = "Merges multiple source records into a single combined record."
    )
    @Responses({
            @Response(responseCode = 200, description = "Merge completed successfully"),
            @Response(responseCode = 400, description = "Eligibility failure or batch ops disabled"),
            @Response(responseCode = 404, description = "One or more source records not found"),
            @Response(responseCode = 500, description = "Server error")
    })
    public ApiResponse mergeRecords(
            @Param(value = "body", description = "JSON body with recordIds and mergedFields") String requestBody
    ) {
        long t0 = System.currentTimeMillis();
        String tableName = getTableName();

        try {
            if (!isBatchOperationsEnabled()) {
                return configError("Batch operations are disabled. Enable 'enableBatchOperations' in plugin properties.", t0);
            }

            JSONObject body = new JSONObject(requestBody);

            // Accept sourceIds (spec primary) with recordIds fallback
            JSONArray idsArr = body.optJSONArray("sourceIds");
            if (idsArr == null || idsArr.length() == 0) {
                idsArr = body.optJSONArray("recordIds");
            }
            if (idsArr == null || idsArr.length() == 0) {
                return validationError("Missing or empty sourceIds/recordIds array", t0);
            }

            List<String> sourceIds = new ArrayList<>();
            for (int i = 0; i < idsArr.length(); i++) {
                sourceIds.add(idsArr.getString(i));
            }

            Map<String, String> mergedFields = new HashMap<>();
            JSONObject mfObj = body.optJSONObject("mergedFields");
            if (mfObj != null) {
                Iterator<String> keys = mfObj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    mergedFields.put(key, mfObj.optString(key, ""));
                }
            }

            ValidationConfig config = getValidationConfig();
            Map<String, Object> result = SERVICE.mergeRecords(
                    tableName, sourceIds, mergedFields, config);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (RecordNotFoundException e) {
            return notFound(e.getMessage(), t0);

        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("has status") || msg.contains("does not allow"))) {
                return invalidStatus(msg, t0);
            }
            return validationFailed(msg, t0);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error merging records");
            return databaseError("Merge failed: " + e.getMessage(), t0);
        }
    }

    // ── Save param dispatcher (inline save or batch action) ────────────────

    /**
     * Dispatches the 'save' query param based on JSON content.
     * Dispatch order (most specific first):
     * 1. create (has create + fields)
     * 2. split (has split + id + allocations)
     * 3. merge (has merge + sourceIds/recordIds)
     * 4. delete (has delete + id)
     * 5. statusTransition (has statusTransition + id + targetStatus)
     * 6. confirm (has confirm + recordIds)
     * 7. batch transition (has targetStatus + recordIds)
     * 8. inline save (fallback)
     */
    private ApiResponse dispatchSaveParam(String json) {
        try {
            JSONObject peek = new JSONObject(json);
            if (peek.has("create") && peek.has("fields")) {
                return handleCreate(json);
            }
            if (peek.has("split") && peek.has("id") && peek.has("allocations")) {
                return handleSplit(json);
            }
            if (peek.has("merge") && (peek.has("sourceIds") || peek.has("recordIds"))) {
                return handleMerge(json);
            }
            if (peek.has("delete") && peek.has("id")) {
                return handleDelete(json);
            }
            if (peek.has("statusTransition") && peek.has("id") && peek.has("targetStatus")) {
                return handleStatusTransition(json);
            }
            if (peek.has("confirm") && peek.has("recordIds")) {
                return handleConfirm(json);
            }
            if (peek.has("targetStatus") && peek.has("recordIds")) {
                return handleBatchAction(json);
            }
            return handleInlineSave(json);
        } catch (Exception e) {
            long t0 = System.currentTimeMillis();
            return validationError("Invalid JSON in save parameter: " + e.getMessage(), t0);
        }
    }

    // ── Create record ──────────────────────────────────────────────────────

    /**
     * Handles record creation via the save query param dispatch.
     * JSON: {create:true, fields:{internal_type:"...", debit_credit:"D", ...}}
     */
    private ApiResponse handleCreate(String createJson) {
        long t0 = System.currentTimeMillis();
        String tableName = getTableName();

        try {
            JSONObject body = new JSONObject(createJson);
            JSONObject fieldsObj = body.optJSONObject("fields");
            if (fieldsObj == null || fieldsObj.length() == 0) {
                return validationError("Missing or empty 'fields' object", t0);
            }

            Map<String, String> fields = new HashMap<>();
            Iterator<String> keys = fieldsObj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                fields.put(key, fieldsObj.optString(key, ""));
            }

            Map<String, Object> result = SERVICE.createRecord(tableName, fields);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(201, result);

        } catch (IllegalArgumentException e) {
            return validationFailed(e.getMessage(), t0);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error creating record via dispatch");
            return databaseError("Create failed: " + e.getMessage(), t0);
        }
    }

    // ── Inline save ─────────────────────────────────────────────────────────

    /**
     * Handles inline save via the records endpoint.
     * The 'save' parameter contains a JSON string with id, version, and fields to update.
     */
    private ApiResponse handleInlineSave(String saveJson) {
        long t0 = System.currentTimeMillis();
        String tableName = getTableName();

        try {
            JSONObject body = new JSONObject(saveJson);

            String id = body.optString("id", "");
            if (id.isEmpty()) {
                return validationError("Missing required field: id", t0);
            }
            if (!body.has("version")) {
                return validationError("Missing required field: version", t0);
            }
            int expectedVersion = body.getInt("version");

            Map<String, String> fieldsToUpdate = new HashMap<>();
            Iterator<String> keys = body.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("id".equals(key) || "version".equals(key)) {
                    continue;
                }
                fieldsToUpdate.put(key, body.optString(key, ""));
            }

            ValidationConfig config = getValidationConfig();
            FormRow updated = SERVICE.updateRecord(
                    tableName, id, fieldsToUpdate, expectedVersion,
                    config.getConfidenceOverrides());

            Map<String, Object> result = rowToMap(updated);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (RecordNotFoundException e) {
            return notFound(e.getMessage(), t0);

        } catch (VersionConflictException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "VERSION_CONFLICT");
            err.put("message", e.getMessage());
            err.put("currentVersion", e.getCurrentVersion());
            err.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(409, err);

        } catch (TerminalStatusException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "TERMINAL_STATUS");
            err.put("message", e.getMessage());
            err.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(400, err);

        } catch (InvalidTransitionException e) {
            Set<Status> valid = STATUS_MANAGER.getValidTransitions(
                    EntityType.ENRICHMENT, e.getFromStatus());
            List<String> validCodes = new ArrayList<>();
            for (Status s : valid) {
                validCodes.add(s.getCode());
            }

            Map<String, Object> err = new HashMap<>();
            err.put("error", "INVALID_TRANSITION");
            err.put("message", e.getMessage());
            err.put("currentStatus", e.getFromStatus() != null ? e.getFromStatus().getCode() : null);
            err.put("targetStatus", e.getToStatus() != null ? e.getToStatus().getCode() : null);
            err.put("validTransitions", validCodes);
            err.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(422, err);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error in inline save");
            return databaseError("Error in inline save: " + e.getMessage(), t0);
        }
    }

    // ── Split via dispatch (from /records?save=...) ─────────────────────

    /**
     * Handles split via the save query param dispatch.
     * JSON: {split:true, id:"...", allocations:[...]}
     */
    private ApiResponse handleSplit(String splitJson) {
        long t0 = System.currentTimeMillis();
        String tableName = getTableName();

        try {
            if (!isBatchOperationsEnabled()) {
                return configError("Batch operations are disabled. Enable 'enableBatchOperations' in plugin properties.", t0);
            }

            JSONObject body = new JSONObject(splitJson);
            String id = body.optString("id", "");
            if (id.isEmpty()) {
                return validationError("Missing required field: id", t0);
            }

            JSONArray allocArr = body.optJSONArray("allocations");
            if (allocArr == null || allocArr.length() == 0) {
                return validationError("Missing or empty allocations array", t0);
            }

            List<Map<String, String>> allocations = new ArrayList<>();
            for (int i = 0; i < allocArr.length(); i++) {
                JSONObject allocObj = allocArr.getJSONObject(i);
                Map<String, String> alloc = new HashMap<>();
                Iterator<String> keys = allocObj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    alloc.put(key, allocObj.optString(key, ""));
                }
                allocations.add(alloc);
            }

            ValidationConfig config = getValidationConfig();
            Map<String, Object> result = SERVICE.splitRecord(tableName, id, allocations, config);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (RecordNotFoundException e) {
            return notFound(e.getMessage(), t0);

        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("does not allow split")) {
                return invalidStatus(msg, t0);
            }
            return validationFailed(msg, t0);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error splitting record via dispatch");
            return databaseError("Split failed: " + e.getMessage(), t0);
        }
    }

    // ── Merge via dispatch (from /records?save=...) ───────────────────

    /**
     * Handles merge via the save query param dispatch.
     * JSON: {merge:true, sourceIds:"id1,id2", mergedFields:{}}
     */
    private ApiResponse handleMerge(String mergeJson) {
        long t0 = System.currentTimeMillis();
        String tableName = getTableName();

        try {
            if (!isBatchOperationsEnabled()) {
                return configError("Batch operations are disabled. Enable 'enableBatchOperations' in plugin properties.", t0);
            }

            JSONObject body = new JSONObject(mergeJson);

            // Accept sourceIds (comma-sep string) with recordIds fallback
            String idsStr = body.optString("sourceIds", "");
            if (idsStr.isEmpty()) {
                idsStr = body.optString("recordIds", "");
            }
            if (idsStr.isEmpty()) {
                return validationError("Missing or empty sourceIds/recordIds", t0);
            }

            List<String> sourceIds = new ArrayList<>();
            String[] idParts = idsStr.split(",");
            for (String part : idParts) {
                String id = part.trim();
                if (!id.isEmpty()) sourceIds.add(id);
            }

            if (sourceIds.isEmpty()) {
                return validationError("No valid record IDs provided", t0);
            }

            Map<String, String> mergedFields = new HashMap<>();
            JSONObject mfObj = body.optJSONObject("mergedFields");
            if (mfObj != null) {
                Iterator<String> keys = mfObj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    mergedFields.put(key, mfObj.optString(key, ""));
                }
            }

            ValidationConfig config = getValidationConfig();
            Map<String, Object> result = SERVICE.mergeRecords(
                    tableName, sourceIds, mergedFields, config);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (RecordNotFoundException e) {
            return notFound(e.getMessage(), t0);

        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("has status") || msg.contains("does not allow"))) {
                return invalidStatus(msg, t0);
            }
            return validationFailed(msg, t0);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error merging records via dispatch");
            return databaseError("Merge failed: " + e.getMessage(), t0);
        }
    }

    // ── Delete via dispatch (from /records?save=...) ──────────────────

    /**
     * Handles delete via the save query param dispatch.
     * JSON: {delete:true, id:"..."}
     */
    private ApiResponse handleDelete(String deleteJson) {
        long t0 = System.currentTimeMillis();
        String tableName = getTableName();

        try {
            JSONObject body = new JSONObject(deleteJson);
            String id = body.optString("id", "");
            if (id.isEmpty()) {
                return validationError("Missing required field: id", t0);
            }

            SERVICE.deleteRecord(tableName, id);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("message", "Record deleted successfully");
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (RecordNotFoundException e) {
            return notFound(e.getMessage(), t0);

        } catch (DeleteNotAllowedException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "DELETE_NOT_ALLOWED");
            err.put("message", e.getMessage());
            err.put("currentStatus", e.getCurrentStatus());
            err.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(400, err);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error deleting record via dispatch");
            return databaseError("Error deleting record: " + e.getMessage(), t0);
        }
    }

    // ── Status transition via dispatch (from /records?save=...) ───────

    /**
     * Handles single status transition via the save query param dispatch.
     * JSON: {statusTransition:true, id:"...", targetStatus:"...", reason:"..."}
     */
    private ApiResponse handleStatusTransition(String transitionJson) {
        long t0 = System.currentTimeMillis();
        String tableName = getTableName();

        try {
            JSONObject body = new JSONObject(transitionJson);
            String id = body.optString("id", "");
            if (id.isEmpty()) {
                return validationError("Missing required field: id", t0);
            }

            String targetStatusCode = body.optString("targetStatus", "");
            if (targetStatusCode.isEmpty()) {
                return validationError("Missing required field: targetStatus", t0);
            }

            Status targetStatus;
            try {
                targetStatus = Status.fromCode(targetStatusCode);
            } catch (IllegalArgumentException e) {
                return validationError("Unknown status code: " + targetStatusCode, t0);
            }

            String reason = body.optString("reason", "Status transition via workspace");

            Map<String, Object> result = SERVICE.transitionStatus(tableName, id, targetStatus, reason);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (RecordNotFoundException e) {
            return notFound(e.getMessage(), t0);

        } catch (InvalidTransitionException e) {
            Set<Status> valid = STATUS_MANAGER.getValidTransitions(
                    EntityType.ENRICHMENT, e.getFromStatus());
            List<String> validCodes = new ArrayList<>();
            for (Status s : valid) {
                validCodes.add(s.getCode());
            }

            Map<String, Object> err = new HashMap<>();
            err.put("error", "INVALID_TRANSITION");
            err.put("message", e.getMessage());
            err.put("currentStatus", e.getFromStatus() != null ? e.getFromStatus().getCode() : null);
            err.put("targetStatus", e.getToStatus() != null ? e.getToStatus().getCode() : null);
            err.put("validTransitions", validCodes);
            err.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(422, err);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error transitioning status via dispatch");
            return databaseError("Status transition failed: " + e.getMessage(), t0);
        }
    }

    // ── Batch action (dispatched from /records?save=...) ──────────────────

    /**
     * Handles batch status transition via the records endpoint.
     * JSON contains targetStatus, recordIds (comma-separated string), and optional reason.
     * Comma-separated string avoids [] brackets in URL which Tomcat rejects.
     */
    private ApiResponse handleBatchAction(String actionJson) {
        long t0 = System.currentTimeMillis();
        String tableName = getTableName();

        try {
            JSONObject body = new JSONObject(actionJson);

            String idsStr = body.optString("recordIds", "");
            if (idsStr.isEmpty()) {
                return validationError("Missing or empty recordIds", t0);
            }

            String targetStatusCode = body.optString("targetStatus", "");
            if (targetStatusCode.isEmpty()) {
                return validationError("Missing required field: targetStatus", t0);
            }

            Status targetStatus;
            try {
                targetStatus = Status.fromCode(targetStatusCode);
            } catch (IllegalArgumentException e) {
                return validationError("Unknown status code: " + targetStatusCode, t0);
            }

            String reason = body.optString("reason", "Batch status transition via workspace");

            List<String> recordIds = new ArrayList<>();
            String[] idParts = idsStr.split(",");
            for (int i = 0; i < idParts.length; i++) {
                String id = idParts[i].trim();
                if (!id.isEmpty()) recordIds.add(id);
            }

            if (recordIds.isEmpty()) {
                return validationError("No valid record IDs provided", t0);
            }

            Map<String, Object> result = SERVICE.batchTransitionStatus(
                    tableName, recordIds, targetStatus, reason);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error in batch action");
            return databaseError("Batch action failed: " + e.getMessage(), t0);
        }
    }

    // ── Confirm for posting (piggyback) ─────────────────────────────────────

    /**
     * Handles confirm-for-posting via the save query param piggyback.
     * Parses comma-separated recordIds string, delegates to SERVICE.confirmRecords().
     */
    private ApiResponse handleConfirm(String confirmJson) {
        long t0 = System.currentTimeMillis();
        String tableName = getTableName();

        try {
            if (!isBatchOperationsEnabled()) {
                return configError("Batch operations are disabled. Enable 'enableBatchOperations' in plugin properties.", t0);
            }

            JSONObject body = new JSONObject(confirmJson);

            String idsStr = body.optString("recordIds", "");
            if (idsStr.isEmpty()) {
                return validationError("Missing or empty recordIds", t0);
            }

            boolean allowPartial = body.optBoolean("allowPartial", false);

            List<String> recordIds = new ArrayList<>();
            String[] idParts = idsStr.split(",");
            for (int i = 0; i < idParts.length; i++) {
                String id = idParts[i].trim();
                if (!id.isEmpty()) recordIds.add(id);
            }

            if (recordIds.isEmpty()) {
                return validationError("No valid record IDs provided", t0);
            }

            ValidationConfig config = getValidationConfig();
            Map<String, Object> result = SERVICE.confirmRecords(
                    tableName, recordIds, allowPartial, config);

            // If confirmed:0 with validation errors, return 400
            Object confirmedCount = result.get("confirmed");
            List<?> valErrors = (List<?>) result.get("validationErrors");
            if (confirmedCount != null && ((Number) confirmedCount).intValue() == 0
                    && valErrors != null && !valErrors.isEmpty()) {
                result.put("error", "VALIDATION_FAILED");
                result.put("message", "Confirmation failed: validation errors");
                result.put("ms", System.currentTimeMillis() - t0);
                return new ApiResponse(400, result);
            }

            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error confirming records");
            return databaseError("Confirmation failed: " + e.getMessage(), t0);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static boolean isStandardField(String field) {
        return "id".equals(field) || "dateCreated".equals(field)
                || "dateModified".equals(field) || "createdBy".equals(field)
                || "modifiedBy".equals(field);
    }

    private ApiResponse validationError(String message, long t0) {
        Map<String, Object> err = new HashMap<>();
        err.put("error", "VALIDATION_ERROR");
        err.put("message", message);
        err.put("ms", System.currentTimeMillis() - t0);
        return new ApiResponse(400, err);
    }

    private ApiResponse invalidParams(String message, long t0) {
        Map<String, Object> err = new HashMap<>();
        err.put("error", "INVALID_PARAMS");
        err.put("message", message);
        err.put("ms", System.currentTimeMillis() - t0);
        return new ApiResponse(400, err);
    }

    private ApiResponse databaseError(String message, long t0) {
        Map<String, Object> err = new HashMap<>();
        err.put("error", "DATABASE_ERROR");
        err.put("message", message);
        err.put("ms", System.currentTimeMillis() - t0);
        return new ApiResponse(500, err);
    }

    private ApiResponse configError(String message, long t0) {
        Map<String, Object> err = new HashMap<>();
        err.put("error", "CONFIG_ERROR");
        err.put("message", message);
        err.put("ms", System.currentTimeMillis() - t0);
        return new ApiResponse(400, err);
    }

    private ApiResponse validationFailed(String message, long t0) {
        Map<String, Object> err = new HashMap<>();
        err.put("error", "VALIDATION_FAILED");
        err.put("message", message);
        err.put("ms", System.currentTimeMillis() - t0);
        return new ApiResponse(400, err);
    }

    private ApiResponse invalidStatus(String message, long t0) {
        Map<String, Object> err = new HashMap<>();
        err.put("error", "INVALID_STATUS");
        err.put("message", message);
        err.put("ms", System.currentTimeMillis() - t0);
        return new ApiResponse(400, err);
    }

    private ApiResponse notFound(String message, long t0) {
        Map<String, Object> err = new HashMap<>();
        err.put("error", "NOT_FOUND");
        err.put("message", message);
        err.put("ms", System.currentTimeMillis() - t0);
        return new ApiResponse(404, err);
    }

    /** Parse a numeric value from a form field, returning 0.0 for null/empty/non-numeric. */
    private static double parseDouble(Object val) {
        if (val == null) return 0.0;
        String s = val.toString().trim();
        if (s.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Converts a FormRow into a flat map for JSON response.
     */
    private Map<String, Object> rowToMap(FormRow row) {
        Map<String, Object> m = new HashMap<>();

        m.put("id", row.getId());
        if (row.getDateCreated() != null) {
            m.put("dateCreated", row.getDateCreated().getTime());
        }
        if (row.getDateModified() != null) {
            m.put("dateModified", row.getDateModified().getTime());
        }
        m.put("createdBy", row.getCreatedBy());
        m.put("modifiedBy", row.getModifiedBy());

        for (Object key : row.getCustomProperties().keySet()) {
            String fieldId = key.toString();
            if ("id".equals(fieldId) || "dateCreated".equals(fieldId)
                    || "dateModified".equals(fieldId) || "createdBy".equals(fieldId)
                    || "modifiedBy".equals(fieldId)) {
                continue;
            }
            Object val = row.getProperty(fieldId);
            m.put(fieldId, val != null ? val : null);
        }

        return m;
    }
}
