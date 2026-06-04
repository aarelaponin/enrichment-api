package org.joget.gam.enrichment.api;


import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
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
import org.joget.gam.enrichment.service.JdbcHelper;
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

    // Stamped automatically from Maven (${maven.build.timestamp}) at build time, so
    // every build shows a distinct id in Manage Plugins (project rule).
    private static final String BUILD = loadBuild();

    private static String loadBuild() {
        try (java.io.InputStream in = EnrichmentApiPlugin.class.getResourceAsStream("/build-info.properties")) {
            if (in != null) {
                java.util.Properties p = new java.util.Properties();
                p.load(in);
                String b = p.getProperty("build");
                if (b != null && !b.isEmpty() && !b.startsWith("${")) return b;
            }
        } catch (Exception ignored) {
        }
        return "dev";
    }

    @Override
    public String getName() {
        return "enrichment-api";
    }

    @Override
    public String getVersion() {
        return "1.0.0 build " + BUILD;
    }

    @Override
    public String getDescription() {
        return "REST API for enrichment workspace operations. Build " + BUILD + ".";
    }

    @Override
    public String getLabel() {
        return "Enrichment API (build " + BUILD + ")";
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
            @Param(value = "dateFrom", required = false,
                    description = "Filter: transaction_date >= this ISO date (yyyy-MM-dd)") String dateFrom,
            @Param(value = "dateTo", required = false,
                    description = "Filter: transaction_date <= this ISO date (yyyy-MM-dd)") String dateTo,
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

            // Date-range filter on transaction_date. Stored ISO (yyyy-MM-dd), so a
            // lexicographic >=/<= comparison is also chronological.
            if (dateFrom != null && !dateFrom.trim().isEmpty()) {
                cond.append(cond.length() == 0 ? "WHERE " : " AND ");
                cond.append("e.customProperties.transaction_date >= ?");
                params.add(dateFrom.trim());
            }
            if (dateTo != null && !dateTo.trim().isEmpty()) {
                cond.append(cond.length() == 0 ? "WHERE " : " AND ");
                cond.append("e.customProperties.transaction_date <= ?");
                params.add(dateTo.trim());
            }

            if (search != null && !search.trim().isEmpty()) {
                String[] kv = search.split(":", 2);
                if (kv.length == 2 && !kv[0].trim().isEmpty() && !kv[1].trim().isEmpty()) {
                    String fieldName = kv[0].trim();
                    if (!SAFE_FIELD_NAME.matcher(fieldName).matches()) {
                        return invalidParams("Invalid search field name: " + fieldName, t0);
                    }
                    if ("customer_code".equals(fieldName)) {
                        // Resolve customer code search to UUIDs via customer form
                        List<String> custUuids = lookupCustomersByCode(kv[1].trim());
                        if (!custUuids.isEmpty()) {
                            cond.append(cond.length() == 0 ? "WHERE " : " AND ");
                            cond.append("e.customProperties.resolved_customer_id IN (");
                            for (int ci = 0; ci < custUuids.size(); ci++) {
                                if (ci > 0) cond.append(",");
                                cond.append("?");
                                params.add(custUuids.get(ci));
                            }
                            cond.append(")");
                        } else {
                            // No matching customers — force empty result
                            cond.append(cond.length() == 0 ? "WHERE " : " AND ");
                            cond.append("1=0");
                        }
                    } else {
                        cond.append(cond.length() == 0 ? "WHERE " : " AND ");
                        cond.append("e.customProperties.").append(fieldName).append(" LIKE ?");
                        params.add("%" + kv[1].trim() + "%");
                    }
                }
            }

            String condition = cond.length() > 0 ? cond.toString() : "";
            Object[] paramsArr = params.isEmpty() ? new Object[0] : params.toArray();

            FormDataDao dao = (FormDataDao) AppUtil.getApplicationContext().getBean("formDataDao");

            int start = (pg - 1) * ps;
            long total = dao.count(null, tableName, condition, paramsArr);

            List<Map<String, Object>> records = new ArrayList<>();

            // Sort in Java over the full result set. Joget stores every value as
            // VARCHAR and dao.find(formDefId=null, ...) does not reliably ORDER BY
            // custom fields, so server-side sort there is unreliable. The data volume
            // here is small (hundreds of rows), so we load all matching rows, sort in
            // Java (numeric for amount-like fields, case-insensitive string otherwise),
            // then paginate. This makes EVERY sortable column work consistently.
            {
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
                final boolean numeric = NUMERIC_SORT_FIELDS.contains(sortField);
                all.sort((a, b) -> {
                    int c;
                    if (numeric) {
                        c = Double.compare(parseDouble(a.get(sf)), parseDouble(b.get(sf)));
                    } else {
                        Object oa = a.get(sf), ob = b.get(sf);
                        String va = oa == null ? "" : oa.toString();
                        String vb = ob == null ? "" : ob.toString();
                        c = va.compareToIgnoreCase(vb);
                    }
                    return isDesc ? -c : c;
                });
                int from = Math.min(start, all.size());
                int to = Math.min(start + ps, all.size());
                records = new ArrayList<>(all.subList(from, to));
            }

            int totalPages = ps > 0 ? (int) Math.ceil((double) total / ps) : 0;

            // Resolve customer UUIDs to human-readable codes
            resolveCustomerCodes(records);

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

            // Resolve customer UUID to human-readable code
            List<Map<String, Object>> single = new ArrayList<>();
            single.add(result);
            resolveCustomerCodes(single);

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
            // === Trade Allocation (D1) ===
            if (peek.has("allocateTrade") && peek.has("enrichmentId") && peek.has("customerId")) {
                return handleAllocateTrade(json);
            }
            // === Automatic Trade Allocation (D4) — capital share (BUY) / holdings (SELL) ===
            if (peek.has("autoAllocateTrades")) {
                return handleAutoAllocateTrades(json);
            }
            if (peek.has("autoAllocateTrade") && peek.has("enrichmentId")) {
                return handleAutoAllocateTrade(json);
            }
            if (peek.has("secuTransaction") && peek.has("enrichmentId")) {
                return handleGetSecuTransaction(json);
            }
            if (peek.has("assetHolders") && peek.has("enrichmentId")) {
                return handleGetAssetHolders(json);
            }
            if (peek.has("allocationSummary") && peek.has("enrichmentId")) {
                return handleGetAllocationSummary(json);
            }
            // === Income Allocation (D2) ===
            if (peek.has("allocateIncome") && peek.has("enrichmentId")) {
                return handleAllocateIncome(json);
            }
            if (peek.has("previewIncomeAllocation") && peek.has("enrichmentId")) {
                return handlePreviewIncomeAllocation(json);
            }
            if (peek.has("incomeAllocationSummary") && peek.has("enrichmentId")) {
                return handleGetIncomeAllocationSummary(json);
            }

            if (peek.has("customers")) {
                return handleGetCustomers(json);
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

    // ── Trade Allocation handlers (D1) ─────────────────────────────────────

    private ApiResponse handleAllocateTrade(String json) {
        long t0 = System.currentTimeMillis();
        try {
            JSONObject req = new JSONObject(json);
            String enrichmentId = req.getString("enrichmentId");
            String customerId = req.getString("customerId");
            BigDecimal quantity = new BigDecimal(req.getString("quantity"));

            ValidationConfig config = getValidationConfig();
            Map<String, Object> result = SERVICE.allocateTrade(
                    getTableName(), enrichmentId, customerId, quantity, config);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (RecordNotFoundException e) {
            return notFound(e.getMessage(), t0);
        } catch (IllegalArgumentException | IllegalStateException e) {
            LogUtil.warn(CLASS_NAME, "allocateTrade validation: " + e.getMessage());
            return validationFailed(e.getMessage(), t0);
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "allocateTrade failed");
            return databaseError("Allocation failed: " + e.getMessage(), t0);
        } catch (Throwable t) {
            LogUtil.error(CLASS_NAME, new RuntimeException(t), "allocateTrade failed (" + t.getClass().getName() + ")");
            return databaseError("Allocation failed (system): " + t.getMessage(), t0);
        }
    }

    // ── Automatic Trade Allocation handlers (D4) ───────────────────────────

    /** Auto-allocate every eligible, not-yet-allocated trade by capital share / holdings. */
    private ApiResponse handleAutoAllocateTrades(String json) {
        long t0 = System.currentTimeMillis();
        try {
            ValidationConfig config = getValidationConfig();
            Map<String, Object> result = SERVICE.autoAllocateAllTrades(getTableName(), config);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "autoAllocateTrades failed");
            return databaseError("Auto-allocation failed: " + e.getMessage(), t0);
        } catch (Throwable t) {
            LogUtil.error(CLASS_NAME, new RuntimeException(t), "autoAllocateTrades failed (" + t.getClass().getName() + ")");
            return databaseError("Auto-allocation failed (system): " + t.getMessage(), t0);
        }
    }

    /** Auto-allocate one trade by capital share (BUY) / holdings (SELL). */
    private ApiResponse handleAutoAllocateTrade(String json) {
        long t0 = System.currentTimeMillis();
        try {
            JSONObject req = new JSONObject(json);
            String enrichmentId = req.getString("enrichmentId");
            ValidationConfig config = getValidationConfig();
            Map<String, Object> result = SERVICE.autoAllocateTrade(getTableName(), enrichmentId, config);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);
        } catch (RecordNotFoundException e) {
            return notFound(e.getMessage(), t0);
        } catch (IllegalArgumentException | IllegalStateException e) {
            LogUtil.warn(CLASS_NAME, "autoAllocateTrade validation: " + e.getMessage());
            return validationFailed(e.getMessage(), t0);
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "autoAllocateTrade failed");
            return databaseError("Auto-allocation failed: " + e.getMessage(), t0);
        } catch (Throwable t) {
            LogUtil.error(CLASS_NAME, new RuntimeException(t), "autoAllocateTrade failed (" + t.getClass().getName() + ")");
            return databaseError("Auto-allocation failed (system): " + t.getMessage(), t0);
        }
    }

    private ApiResponse handleGetSecuTransaction(String json) {
        long t0 = System.currentTimeMillis();
        Connection conn = null;
        try {
            JSONObject req = new JSONObject(json);
            String enrichmentId = req.getString("enrichmentId");
            ValidationConfig config = getValidationConfig();
            ValidationConfig.AllocationConfig ac = config.getAllocation();

            conn = JdbcHelper.getConnection();

            Map<String, String> enrichment = JdbcHelper.loadRowByFieldId(
                    conn, getTableName(), enrichmentId);
            if (enrichment == null) {
                return notFound("Enrichment record not found: " + enrichmentId, t0);
            }

            String sourceId = enrichment.get(ac.getEnrichmentSourceField());
            if (sourceId == null || sourceId.isEmpty()) {
                return validationError("Enrichment record has no linked securities transaction", t0);
            }

            Map<String, String> secu = JdbcHelper.loadRowByFieldId(
                    conn, ac.getSecuTable(), sourceId);
            if (secu == null) {
                return validationError("Securities transaction not found: " + sourceId, t0);
            }

            Map<String, Object> result = new LinkedHashMap<>();
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
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "getSecuTransaction failed");
            return databaseError("Failed to load secu transaction: " + e.getMessage(), t0);
        } finally {
            JdbcHelper.closeQuietly(conn);
        }
    }

    /**
     * Return the customers who currently HOLD the asset of the given trade
     * (open position quantity > 0), with their available quantity. Used to
     * constrain the SELL allocation dropdown so a sale can only be allocated to
     * an actual holder (a long-only fund cannot sell what a customer does not own).
     */
    private ApiResponse handleGetAssetHolders(String json) {
        long t0 = System.currentTimeMillis();
        Connection conn = null;
        try {
            JSONObject req = new JSONObject(json);
            String enrichmentId = req.getString("enrichmentId");
            ValidationConfig config = getValidationConfig();
            ValidationConfig.AllocationConfig ac = config.getAllocation();

            conn = JdbcHelper.getConnection();
            List<Map<String, Object>> holders = new ArrayList<>();

            Map<String, String> en = JdbcHelper.loadRowByFieldId(conn, getTableName(), enrichmentId);
            if (en != null) {
                String assetId = en.get(ac.getEnrichmentAssetField());
                if (assetId != null && !assetId.isEmpty()) {
                    List<Map<String, String>> positions = JdbcHelper.loadRowsByField(
                            conn, ac.getPositionTable(), "assetId", assetId);
                    for (Map<String, String> pos : positions) {
                        String qtyStr = pos.get("quantity");
                        double qty = (qtyStr != null && !qtyStr.isEmpty()) ? parseDouble(qtyStr) : 0;
                        if (qty > 0) {
                            Map<String, Object> ho = new LinkedHashMap<>();
                            ho.put("customerId", pos.get("customerId"));
                            ho.put("displayName", pos.get("customerDisplayName"));
                            ho.put("quantity", qty);
                            holders.add(ho);
                        }
                    }
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("holders", holders);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "getAssetHolders failed");
            return databaseError("Failed to load asset holders: " + e.getMessage(), t0);
        } finally {
            JdbcHelper.closeQuietly(conn);
        }
    }

    private ApiResponse handleGetAllocationSummary(String json) {
        long t0 = System.currentTimeMillis();
        Connection conn = null;
        try {
            JSONObject req = new JSONObject(json);
            String enrichmentId = req.getString("enrichmentId");
            ValidationConfig config = getValidationConfig();
            ValidationConfig.AllocationConfig ac = config.getAllocation();

            conn = JdbcHelper.getConnection();

            List<Map<String, String>> lots = JdbcHelper.loadRowsByField(
                    conn, ac.getLotTable(), "sourceEnrichmentId", enrichmentId);

            // Collect unique customer IDs and batch-load display names
            Set<String> customerIds = new HashSet<>();
            for (Map<String, String> lot : lots) {
                String cid = lot.get("customerId");
                if (cid != null && !cid.isEmpty()) customerIds.add(cid);
            }
            Map<String, String> customerNames = new HashMap<>();
            for (String cid : customerIds) {
                Map<String, String> cust = JdbcHelper.loadRowByField(conn, ac.getCustomerTable(), "customerId", cid);
                if (cust != null) {
                    customerNames.put(cid, cust.get(ac.getCustomerDisplayNameField()));
                }
            }

            BigDecimal totalQty = BigDecimal.ZERO;
            BigDecimal totalAmount = BigDecimal.ZERO;
            BigDecimal totalFee = BigDecimal.ZERO;
            List<Map<String, Object>> lotList = new ArrayList<>();
            for (Map<String, String> lot : lots) {
                String qtyStr = lot.get("quantity");
                BigDecimal qty = (qtyStr != null && !qtyStr.isEmpty())
                        ? new BigDecimal(qtyStr) : BigDecimal.ZERO;
                totalQty = totalQty.add(qty);

                double lotTotalAmount = parseDouble(lot.get("totalAmount"));
                double lotFeeAmount = parseDouble(lot.get("feeAmount"));
                totalAmount = totalAmount.add(BigDecimal.valueOf(lotTotalAmount));
                totalFee = totalFee.add(BigDecimal.valueOf(lotFeeAmount));

                Map<String, Object> lotInfo = new LinkedHashMap<>();
                lotInfo.put("lotId", lot.get("id"));
                lotInfo.put("customerId", lot.get("customerId"));
                lotInfo.put("customerName", customerNames.get(lot.get("customerId")));
                lotInfo.put("quantity", qty.doubleValue());
                lotInfo.put("direction", lot.get("direction"));
                lotInfo.put("totalAmount", lotTotalAmount);
                lotInfo.put("feeAmount", lotFeeAmount);
                lotInfo.put("totalCostWithFees", parseDouble(lot.get("totalCostWithFees")));
                lotInfo.put("currency", lot.get("currency"));
                lotInfo.put("allocationDate", lot.get("allocationDate"));
                lotInfo.put("pricePerUnit", parseDouble(lot.get("pricePerUnit")));
                lotInfo.put("totalAmountEur", parseDouble(lot.get("totalAmountEur")));
                lotInfo.put("feeAmountEur", parseDouble(lot.get("feeAmountEur")));
                lotList.add(lotInfo);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("enrichmentId", enrichmentId);
            result.put("allocatedQty", totalQty.doubleValue());
            result.put("totalAmount", totalAmount.doubleValue());
            result.put("totalFee", totalFee.doubleValue());
            result.put("currency", lots.isEmpty() ? "" : lots.get(0).get("currency"));
            result.put("lotCount", lots.size());
            result.put("lots", lotList);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "getAllocationSummary failed");
            return databaseError("Failed to load allocation summary: " + e.getMessage(), t0);
        } finally {
            JdbcHelper.closeQuietly(conn);
        }
    }

    // ── Income Allocation (D2) handlers ────────────────────────────────

    private ApiResponse handleAllocateIncome(String json) {
        long t0 = System.currentTimeMillis();
        try {
            JSONObject req = new JSONObject(json);
            String enrichmentId = req.getString("enrichmentId");
            String periodStart = req.getString("accrualPeriodStart");
            String periodEnd = req.getString("accrualPeriodEnd");

            ValidationConfig config = getValidationConfig();
            Map<String, Object> result = SERVICE.allocateIncome(
                    getTableName(), enrichmentId, periodStart, periodEnd, false, config);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (RecordNotFoundException e) {
            return notFound(e.getMessage(), t0);
        } catch (IllegalArgumentException | IllegalStateException e) {
            LogUtil.warn(CLASS_NAME, "allocateIncome validation: " + e.getMessage());
            return validationFailed(e.getMessage(), t0);
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "allocateIncome failed");
            return databaseError("Income allocation failed: " + e.getMessage(), t0);
        } catch (Throwable t) {
            LogUtil.error(CLASS_NAME, new RuntimeException(t), "allocateIncome failed (" + t.getClass().getName() + ")");
            return databaseError("Income allocation failed (system): " + t.getMessage(), t0);
        }
    }

    private ApiResponse handlePreviewIncomeAllocation(String json) {
        long t0 = System.currentTimeMillis();
        try {
            JSONObject req = new JSONObject(json);
            String enrichmentId = req.getString("enrichmentId");
            String periodStart = req.getString("accrualPeriodStart");
            String periodEnd = req.getString("accrualPeriodEnd");

            ValidationConfig config = getValidationConfig();
            Map<String, Object> result = SERVICE.allocateIncome(
                    getTableName(), enrichmentId, periodStart, periodEnd, true, config);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (RecordNotFoundException e) {
            return notFound(e.getMessage(), t0);
        } catch (IllegalArgumentException | IllegalStateException e) {
            LogUtil.warn(CLASS_NAME, "previewIncomeAllocation validation: " + e.getMessage());
            return validationFailed(e.getMessage(), t0);
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "previewIncomeAllocation failed");
            return databaseError("Income allocation preview failed: " + e.getMessage(), t0);
        } catch (Throwable t) {
            LogUtil.error(CLASS_NAME, new RuntimeException(t), "previewIncomeAllocation failed (" + t.getClass().getName() + ")");
            return databaseError("Income allocation preview failed (system): " + t.getMessage(), t0);
        }
    }

    private ApiResponse handleGetIncomeAllocationSummary(String json) {
        long t0 = System.currentTimeMillis();
        Connection conn = null;
        try {
            JSONObject req = new JSONObject(json);
            String enrichmentId = req.getString("enrichmentId");
            ValidationConfig config = getValidationConfig();
            ValidationConfig.IncomeAllocationConfig iac = config.getIncomeAllocation();

            conn = JdbcHelper.getConnection();

            List<Map<String, String>> allocations = JdbcHelper.loadRowsByField(
                    conn, iac.getIncomeAllocTable(), "sourceEnrichmentId", enrichmentId);

            BigDecimal totalAllocated = BigDecimal.ZERO;
            BigDecimal totalAllocatedEur = BigDecimal.ZERO;
            List<Map<String, Object>> allocList = new ArrayList<>();

            for (Map<String, String> alloc : allocations) {
                double amt = parseDouble(alloc.get("allocatedAmount"));
                double amtEur = parseDouble(alloc.get("allocatedAmountEur"));
                totalAllocated = totalAllocated.add(BigDecimal.valueOf(amt));
                totalAllocatedEur = totalAllocatedEur.add(BigDecimal.valueOf(amtEur));

                Map<String, Object> a = new LinkedHashMap<>();
                a.put("incomeAllocId", alloc.get("incomeAllocId"));
                a.put("customerId", alloc.get("customerId"));
                a.put("customerName", alloc.get("customerDisplayName"));
                a.put("shareDays", parseDouble(alloc.get("shareDays")));
                a.put("allocationPct", parseDouble(alloc.get("allocationPercentage")));
                a.put("allocatedAmount", amt);
                a.put("allocatedAmountEur", amtEur);
                a.put("holdingDays", parseDouble(alloc.get("holdingDays")));
                a.put("averageQuantityHeld", parseDouble(alloc.get("averageQuantityHeld")));
                a.put("accrualPeriodStart", alloc.get("accrualPeriodStart"));
                a.put("accrualPeriodEnd", alloc.get("accrualPeriodEnd"));
                allocList.add(a);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("enrichmentId", enrichmentId);
            result.put("totalAllocated", totalAllocated.doubleValue());
            result.put("totalAllocatedEur", totalAllocatedEur.doubleValue());
            result.put("currency", allocations.isEmpty() ? "" : allocations.get(0).get("currency"));
            result.put("allocationCount", allocations.size());
            result.put("allocations", allocList);
            result.put("ms", System.currentTimeMillis() - t0);
            return new ApiResponse(200, result);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "getIncomeAllocationSummary failed");
            return databaseError("Failed to load income allocation summary: " + e.getMessage(), t0);
        } finally {
            JdbcHelper.closeQuietly(conn);
        }
    }

    private ApiResponse handleGetCustomers(String json) {
        long t0 = System.currentTimeMillis();
        Connection conn = null;
        try {
            JSONObject req = new JSONObject(json);
            String search = req.optString("search", "");
            ValidationConfig config = getValidationConfig();
            ValidationConfig.AllocationConfig ac = config.getAllocation();

            conn = JdbcHelper.getConnection();

            String sql = "SELECT id, " + JdbcHelper.dbCol("customerId") + ", "
                    + JdbcHelper.dbCol(ac.getCustomerDisplayNameField())
                    + " FROM " + JdbcHelper.dbTable(ac.getCustomerTable())
                    + " WHERE (" + JdbcHelper.dbCol(ac.getCustomerIsFundField()) + " IS NULL"
                    + " OR " + JdbcHelper.dbCol(ac.getCustomerIsFundField()) + " != 'yes')";

            if (!search.isEmpty()) {
                sql += " AND (" + JdbcHelper.dbCol(ac.getCustomerDisplayNameField())
                        + " LIKE ? OR " + JdbcHelper.dbCol("customerId") + " LIKE ?)";
            }
            sql += " ORDER BY " + JdbcHelper.dbCol(ac.getCustomerDisplayNameField());

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (!search.isEmpty()) {
                    ps.setString(1, "%" + search + "%");
                    ps.setString(2, "%" + search + "%");
                }

                try (ResultSet rs = ps.executeQuery()) {
                    List<Map<String, Object>> customers = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("customerId", rs.getString(JdbcHelper.dbCol("customerId")));
                        c.put("displayName", rs.getString(
                                JdbcHelper.dbCol(ac.getCustomerDisplayNameField())));
                        customers.add(c);
                    }

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("customers", customers);
                    result.put("ms", System.currentTimeMillis() - t0);
                    return new ApiResponse(200, result);
                }
            }

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "getCustomers failed");
            return databaseError("Failed to load customers: " + e.getMessage(), t0);
        } finally {
            JdbcHelper.closeQuietly(conn);
        }
    }

    // ── Customer code resolution ──────────────────────────────────────────

    private static String objToStr(Object o) {
        return o != null ? o.toString().trim() : "";
    }

    /**
     * Populates customer_code in response records by resolving resolved_customer_id
     * against the customer form table via direct JDBC.
     *
     * resolved_customer_id can contain either:
     * - A UUID (primary key) set by the enrichment pipeline
     * - A customerId value (e.g. "12345678") set by the form SelectBox (idColumn=customerId)
     *
     * We try both lookups: first by primary key (id), then by customerId field.
     */
    private void resolveCustomerCodes(List<Map<String, Object>> records) {
        Set<String> toResolve = new LinkedHashSet<>();
        for (Map<String, Object> r : records) {
            String rid = objToStr(r.get("resolved_customer_id"));
            String code = objToStr(r.get("customer_code"));
            if (!rid.isEmpty() && !"UNK".equals(rid) && code.isEmpty()) {
                toResolve.add(rid);
            }
        }
        LogUtil.info(CLASS_NAME, "resolveCustomerCodes: " + records.size() + " records, "
                + toResolve.size() + " resolved_customer_id values to look up");
        if (toResolve.isEmpty()) return;

        Map<String, String> map = lookupCustomerCodes(toResolve);

        int applied = 0;
        for (Map<String, Object> r : records) {
            String rid = objToStr(r.get("resolved_customer_id"));
            String code = objToStr(r.get("customer_code"));
            if (!rid.isEmpty() && code.isEmpty() && map.containsKey(rid)) {
                r.put("customer_code", map.get(rid));
                applied++;
            }
        }
        LogUtil.info(CLASS_NAME, "resolveCustomerCodes: applied " + applied + " codes");
    }

    /**
     * JDBC lookup of customer codes. For each value in the set, tries:
     * 1) SELECT by primary key (id) — for pipeline-set UUIDs
     * 2) SELECT by c_customerId — for form-set customerId values
     * Returns a map of resolved_customer_id value → human-readable customer code.
     */
    private Map<String, String> lookupCustomerCodes(Set<String> values) {
        Map<String, String> result = new HashMap<>();
        if (values.isEmpty()) return result;
        LogUtil.info(CLASS_NAME, "lookupCustomerCodes: resolving " + values.size()
                + " values, first=" + values.iterator().next());
        try {
            DataSource ds = (DataSource) AppUtil.getApplicationContext().getBean("setupDataSource");
            try (Connection conn = ds.getConnection()) {
                for (String val : values) {
                    String code = null;
                    // Strategy 1: val is the primary key (UUID from pipeline)
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT c_customerId, c_registrationNumber, c_personalId"
                                    + " FROM app_fd_customer WHERE id = ?")) {
                        ps.setString(1, val);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                code = rs.getString("c_customerId");
                                if (code == null || code.trim().isEmpty()) code = rs.getString("c_registrationNumber");
                                if (code == null || code.trim().isEmpty()) code = rs.getString("c_personalId");
                                if (code != null) code = code.trim();
                                LogUtil.info(CLASS_NAME, "lookupCustomerCodes: id=" + val
                                        + " → found by PK, code=" + code);
                            }
                        }
                    }
                    // Strategy 2: val is already the customerId (from form SelectBox idColumn=customerId)
                    if (code == null || code.isEmpty()) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "SELECT c_customerId, c_registrationNumber, c_personalId"
                                        + " FROM app_fd_customer WHERE c_customerId = ?")) {
                            ps.setString(1, val);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    code = rs.getString("c_customerId");
                                    if (code == null || code.trim().isEmpty()) code = rs.getString("c_registrationNumber");
                                    if (code == null || code.trim().isEmpty()) code = rs.getString("c_personalId");
                                    if (code != null) code = code.trim();
                                    LogUtil.info(CLASS_NAME, "lookupCustomerCodes: id=" + val
                                            + " → found by customerId field, code=" + code);
                                }
                            }
                        }
                    }
                    // Strategy 3: val IS already the customer code — use it directly
                    if (code == null || code.isEmpty()) {
                        LogUtil.warn(CLASS_NAME, "lookupCustomerCodes: id=" + val
                                + " → not found by PK or customerId; using raw value as customer_code");
                        code = val;
                    }
                    if (code != null && !code.isEmpty()) {
                        result.put(val, code);
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "JDBC customer lookup failed");
        }
        LogUtil.info(CLASS_NAME, "lookupCustomerCodes: resolved " + result.size() + "/" + values.size());
        return result;
    }

    /**
     * Search customers by code substring (JDBC). Used for customer_code filter.
     * Returns list of resolved_customer_id values matching the search term,
     * checking both id (PK) and c_customerId since either could be stored.
     */
    private List<String> lookupCustomersByCode(String searchTerm) {
        List<String> ids = new ArrayList<>();
        try {
            DataSource ds = (DataSource) AppUtil.getApplicationContext().getBean("setupDataSource");
            try (Connection conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT id, c_customerId FROM app_fd_customer"
                                 + " WHERE c_customerId LIKE ?"
                                 + " OR c_registrationNumber LIKE ?"
                                 + " OR c_personalId LIKE ? LIMIT 100")) {
                String param = "%" + searchTerm + "%";
                ps.setString(1, param);
                ps.setString(2, param);
                ps.setString(3, param);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        // Add both id and customerId — resolved_customer_id could contain either
                        String pk = rs.getString("id");
                        String custId = rs.getString("c_customerId");
                        if (pk != null && !pk.isEmpty()) ids.add(pk);
                        if (custId != null && !custId.isEmpty() && !custId.equals(pk)) ids.add(custId);
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "JDBC customer search failed for: " + searchTerm);
        }
        return ids;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static boolean isStandardField(String field) {
        return "id".equals(field) || "dateCreated".equals(field)
                || "dateModified".equals(field) || "createdBy".equals(field)
                || "modifiedBy".equals(field);
    }

    /**
     * NOTE: Joget's ApiResponse.write() discards the 'content' field for status >= 400
     * and only uses the 'message' field. Therefore all error helpers MUST use the
     * ApiResponse(int, String) constructor so the error message is included in the
     * response body. The Joget framework serialises it as:
     *   {"code":"400","message":"...","date":"..."}
     */

    private ApiResponse validationError(String message, long t0) {
        return new ApiResponse(400, "VALIDATION_ERROR: " + message);
    }

    private ApiResponse invalidParams(String message, long t0) {
        return new ApiResponse(400, "INVALID_PARAMS: " + message);
    }

    private ApiResponse databaseError(String message, long t0) {
        return new ApiResponse(500, "DATABASE_ERROR: " + message);
    }

    private ApiResponse configError(String message, long t0) {
        return new ApiResponse(400, "CONFIG_ERROR: " + message);
    }

    private ApiResponse validationFailed(String message, long t0) {
        return new ApiResponse(400, "VALIDATION_FAILED: " + message);
    }

    private ApiResponse invalidStatus(String message, long t0) {
        return new ApiResponse(400, "INVALID_STATUS: " + message);
    }

    private ApiResponse notFound(String message, long t0) {
        return new ApiResponse(404, "NOT_FOUND: " + message);
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
