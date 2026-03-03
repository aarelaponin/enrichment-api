package org.joget.gam.enrichment.service;

import com.fiscaladmin.gam.framework.status.EntityType;
import com.fiscaladmin.gam.framework.status.InvalidTransitionException;
import com.fiscaladmin.gam.framework.status.Status;
import com.fiscaladmin.gam.framework.status.StatusManager;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Business logic for enrichment record operations.
 *
 * <p>Instantiated once — Spring context (FormDataDao) is only available
 * at request time inside the OSGi container.</p>
 */
public class EnrichmentService {

    private static final String CLASS_NAME = EnrichmentService.class.getName();

    private static final StatusManager STATUS_MANAGER = new StatusManager();

    private static final Set<Status> DELETABLE_STATUSES =
            EnumSet.of(Status.NEW, Status.ERROR, Status.MANUAL_REVIEW);

    private static final Set<Status> SPLITTABLE_STATUSES =
            EnumSet.of(Status.ENRICHED, Status.ADJUSTED, Status.IN_REVIEW, Status.READY);

    private static final Set<Status> MERGEABLE_STATUSES =
            EnumSet.of(Status.ENRICHED, Status.ADJUSTED, Status.IN_REVIEW);

    // ── Existing methods ───────────────────────────────────────────────

    /**
     * Loads a single record by primary key.
     *
     * @return the FormRow, or null if not found
     */
    public FormRow loadRecord(String tableName, String id) {
        FormDataDao dao = getDao();
        return dao.load(null, tableName, id);
    }

    /**
     * Updates an enrichment record with optimistic locking, confidence overrides,
     * and auto-status transition.
     */
    public FormRow updateRecord(String tableName, String id,
                                Map<String, String> fieldsToUpdate,
                                int expectedVersion,
                                JSONArray confidenceOverrides)
            throws RecordNotFoundException, TerminalStatusException,
                   VersionConflictException, InvalidTransitionException {

        FormDataDao dao = getDao();

        // 1. Load current record
        FormRow row = dao.load(null, tableName, id);
        if (row == null) {
            throw new RecordNotFoundException(id);
        }

        // 2. Terminal check
        String statusCode = row.getProperty("status");
        Status currentStatus = null;
        if (statusCode != null && !statusCode.isEmpty()) {
            currentStatus = Status.fromCode(statusCode);
            if (currentStatus == Status.CONFIRMED || currentStatus == Status.SUPERSEDED) {
                throw new TerminalStatusException(id, currentStatus);
            }
        }

        // 3. Version check (optimistic locking)
        int currentVersion = parseVersion(row.getProperty("version"));
        if (currentVersion != expectedVersion) {
            throw new VersionConflictException(id, currentVersion, expectedVersion);
        }

        // 4. Apply fields — track which fields actually changed
        Set<String> changedFields = new HashSet<>();
        for (Map.Entry<String, String> entry : fieldsToUpdate.entrySet()) {
            String fieldId = entry.getKey();
            String newValue = entry.getValue();
            String oldValue = row.getProperty(fieldId);
            if (oldValue == null) oldValue = "";
            if (newValue == null) newValue = "";
            if (!oldValue.equals(newValue)) {
                row.setProperty(fieldId, newValue);
                changedFields.add(fieldId);
            }
        }

        // 5. Confidence overrides
        if (confidenceOverrides != null) {
            applyConfidenceOverrides(row, changedFields, confidenceOverrides);
        }

        // 6. Validate auto-status transition before saving
        boolean needsAutoTransition = !changedFields.isEmpty()
                && currentStatus == Status.ENRICHED;
        if (needsAutoTransition) {
            if (!STATUS_MANAGER.canTransition(EntityType.ENRICHMENT,
                    Status.ENRICHED, Status.ADJUSTED)) {
                throw new InvalidTransitionException(EntityType.ENRICHMENT, id,
                        Status.ENRICHED, Status.ADJUSTED);
            }
        }

        // 7. Increment version and save field changes
        row.setProperty("version", String.valueOf(currentVersion + 1));
        FormRowSet rowSet = new FormRowSet();
        rowSet.add(row);
        dao.saveOrUpdate(null, tableName, rowSet);

        // 8. Auto-status transition via StatusManager
        if (needsAutoTransition) {
            STATUS_MANAGER.transition(dao, tableName, EntityType.ENRICHMENT, id,
                    Status.ADJUSTED, "enrichment-api",
                    "Auto-transition on inline edit");
        }

        // 9. Reload to return final state
        FormRow result = dao.load(null, tableName, id);
        return result != null ? result : row;
    }

    // ── Step 6: Single status transition ───────────────────────────────

    /**
     * Transitions a single record's status via StatusManager.
     *
     * @return map with id, previousStatus, newStatus, modifiedBy
     */
    public Map<String, Object> transitionStatus(String tableName, String id,
                                                 Status targetStatus, String reason)
            throws RecordNotFoundException, InvalidTransitionException {

        FormDataDao dao = getDao();

        // Load to get current status for the response
        FormRow row = dao.load(null, tableName, id);
        if (row == null) {
            throw new RecordNotFoundException(id);
        }

        String previousStatusCode = row.getProperty("status");
        Status previousStatus = null;
        if (previousStatusCode != null && !previousStatusCode.isEmpty()) {
            previousStatus = Status.fromCode(previousStatusCode);
        }

        // Delegate to StatusManager — validates + writes + audits
        try {
            STATUS_MANAGER.transition(dao, tableName, EntityType.ENRICHMENT, id,
                    targetStatus, "enrichment-api",
                    reason != null ? reason : "Status transition via API");
        } catch (IllegalStateException e) {
            // StatusManager throws IllegalStateException for record not found
            throw new RecordNotFoundException(id);
        }

        // Reload for response
        FormRow updated = dao.load(null, tableName, id);

        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("previousStatus", previousStatus != null ? previousStatus.getCode() : null);
        result.put("newStatus", targetStatus.getCode());
        result.put("modifiedBy", updated != null ? updated.getModifiedBy() : null);
        return result;
    }

    // ── Step 7: Batch status transition ────────────────────────────────

    /**
     * Applies the same status transition to multiple records.
     * Failures are collected, not thrown.
     */
    public Map<String, Object> batchTransitionStatus(String tableName, List<String> recordIds,
                                                      Status targetStatus, String reason) {
        List<Map<String, Object>> succeeded = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();

        for (String recordId : recordIds) {
            try {
                Map<String, Object> result = transitionStatus(tableName, recordId, targetStatus, reason);
                succeeded.add(result);
            } catch (RecordNotFoundException e) {
                Map<String, Object> err = new HashMap<>();
                err.put("id", recordId);
                err.put("error", "Record not found");
                failed.add(err);
            } catch (InvalidTransitionException e) {
                Map<String, Object> err = new HashMap<>();
                err.put("id", recordId);
                err.put("currentStatus", e.getFromStatus() != null ? e.getFromStatus().getCode() : null);
                err.put("error", e.getMessage());
                failed.add(err);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("succeeded", succeeded);
        result.put("failed", failed);
        return result;
    }

    // ── Step 8: Delete record ──────────────────────────────────────────

    /**
     * Deletes a record. Only allowed for statuses: new, error, manual_review.
     */
    public void deleteRecord(String tableName, String id)
            throws RecordNotFoundException, DeleteNotAllowedException {

        FormDataDao dao = getDao();

        FormRow row = dao.load(null, tableName, id);
        if (row == null) {
            throw new RecordNotFoundException(id);
        }

        String statusCode = row.getProperty("status");
        if (statusCode != null && !statusCode.isEmpty()) {
            try {
                Status status = Status.fromCode(statusCode);
                if (!DELETABLE_STATUSES.contains(status)) {
                    throw new DeleteNotAllowedException(id, statusCode);
                }
            } catch (IllegalArgumentException e) {
                // Unknown status — allow deletion
            }
        }

        dao.delete(null, tableName, new String[]{id});
    }

    // ── Step 9: Summary ────────────────────────────────────────────────

    /**
     * Returns per-statement summary counts using JDBC GROUP BY.
     */
    public List<Map<String, Object>> getSummary(String tableName,
                                                 ValidationConfig config) throws SQLException {
        ValidationConfig.ReconciliationConfig recon = config.getReconciliation();
        String stmtCol = JdbcHelper.dbCol(recon != null ? recon.getStatementField() : "statement_id");
        String statusCol = JdbcHelper.dbCol(recon != null ? recon.getStatusField() : "status");
        String dbTableName = JdbcHelper.dbTable(tableName);

        String sql = "SELECT " + stmtCol + " AS stmt_id, "
                + "COUNT(CASE WHEN " + statusCol + " NOT IN ('superseded') THEN 1 END) AS total, "
                + "COUNT(CASE WHEN " + statusCol + " = 'new' THEN 1 END) AS cnt_new, "
                + "COUNT(CASE WHEN " + statusCol + " IN ('enriched','adjusted','in_review','paired','manual_review') THEN 1 END) AS working, "
                + "COUNT(CASE WHEN " + statusCol + " = 'ready' THEN 1 END) AS ready, "
                + "COUNT(CASE WHEN " + statusCol + " = 'confirmed' THEN 1 END) AS confirmed, "
                + "COUNT(CASE WHEN " + statusCol + " = 'error' THEN 1 END) AS error "
                + "FROM " + dbTableName + " "
                + "WHERE " + statusCol + " != 'superseded' "
                + "GROUP BY " + stmtCol + " "
                + "ORDER BY " + stmtCol;

        List<Map<String, Object>> statements = new ArrayList<>();
        Connection conn = null;
        try {
            conn = JdbcHelper.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("statementId", rs.getString("stmt_id"));
                    row.put("total", rs.getLong("total"));
                    row.put("new", rs.getLong("cnt_new"));
                    row.put("working", rs.getLong("working"));
                    row.put("ready", rs.getLong("ready"));
                    row.put("confirmed", rs.getLong("confirmed"));
                    row.put("error", rs.getLong("error"));
                    statements.add(row);
                }
            }
        } finally {
            JdbcHelper.closeQuietly(conn);
        }
        return statements;
    }

    // ── Step 10: Reconciliation ────────────────────────────────────────

    /**
     * Computes per-currency reconciliation for a statement.
     */
    public Map<String, Object> getReconciliation(String tableName, String statementId,
                                                  ValidationConfig config) throws SQLException {
        ValidationConfig.ReconciliationConfig recon = config.getReconciliation();
        if (recon == null) {
            throw new IllegalStateException("Reconciliation config is not set");
        }

        Connection conn = null;
        try {
            conn = JdbcHelper.getConnection();

            // 1. Source input (from source tables like F01.03, F01.04)
            Map<String, BigDecimal> sourceInput = new HashMap<>();
            for (ValidationConfig.SourceTableConfig src : recon.getSourceTables()) {
                String sql = "SELECT " + JdbcHelper.dbCol(src.getCurrencyField()) + " AS ccy, "
                        + "SUM(CAST(" + JdbcHelper.dbCol(src.getAmountField()) + " AS DECIMAL(20,4))) AS amt "
                        + "FROM " + JdbcHelper.dbTable(src.getTableName()) + " "
                        + "WHERE " + JdbcHelper.dbCol(src.getStatementField()) + " = ? "
                        + "GROUP BY " + JdbcHelper.dbCol(src.getCurrencyField());
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, statementId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String ccy = rs.getString("ccy");
                            BigDecimal amt = rs.getBigDecimal("amt");
                            if (ccy != null && amt != null) {
                                sourceInput.merge(ccy, amt, BigDecimal::add);
                            }
                        }
                    }
                }
            }

            String mainTable = JdbcHelper.dbTable(tableName);
            String stmtCol = JdbcHelper.dbCol(recon.getStatementField());
            String ccyCol = JdbcHelper.dbCol(recon.getCurrencyField());
            String amtCol = JdbcHelper.dbCol(recon.getAmountField());
            String originCol = JdbcHelper.dbCol(recon.getOriginField());
            String statusCol = JdbcHelper.dbCol(recon.getStatusField());

            // 2. Manual adjustments
            Map<String, BigDecimal> manualAdj = querySumByCurrency(conn, mainTable, amtCol, ccyCol,
                    stmtCol + " = ? AND " + originCol + " = ? AND " + statusCol + " != 'superseded'",
                    new String[]{statementId, recon.getManualOriginValue()});

            // 3. Output (confirmed)
            Map<String, BigDecimal> output = querySumByCurrency(conn, mainTable, amtCol, ccyCol,
                    stmtCol + " = ? AND " + statusCol + " = 'confirmed'",
                    new String[]{statementId});

            // 4. Remaining (active non-confirmed, non-superseded)
            Map<String, BigDecimal> remaining = querySumByCurrency(conn, mainTable, amtCol, ccyCol,
                    stmtCol + " = ? AND " + statusCol + " NOT IN ('confirmed', 'superseded')",
                    new String[]{statementId});

            // 5. Compute per currency
            Set<String> allCurrencies = new HashSet<>();
            allCurrencies.addAll(sourceInput.keySet());
            allCurrencies.addAll(manualAdj.keySet());
            allCurrencies.addAll(output.keySet());
            allCurrencies.addAll(remaining.keySet());

            List<Map<String, Object>> currencies = new ArrayList<>();
            boolean isFinal = true;

            for (String ccy : allCurrencies) {
                BigDecimal si = sourceInput.getOrDefault(ccy, BigDecimal.ZERO);
                BigDecimal ma = manualAdj.getOrDefault(ccy, BigDecimal.ZERO);
                BigDecimal ai = si.add(ma);
                BigDecimal out = output.getOrDefault(ccy, BigDecimal.ZERO);
                BigDecimal rem = remaining.getOrDefault(ccy, BigDecimal.ZERO);
                BigDecimal disc = ai.subtract(out).subtract(rem);
                double tol = recon.getTolerance(ccy);

                if (rem.compareTo(BigDecimal.ZERO) != 0) {
                    isFinal = false;
                }

                Map<String, Object> ccyMap = new LinkedHashMap<>();
                ccyMap.put("currency", ccy);
                ccyMap.put("sourceInput", si.doubleValue());
                ccyMap.put("manualAdj", ma.doubleValue());
                ccyMap.put("adjustedInput", ai.doubleValue());
                ccyMap.put("output", out.doubleValue());
                ccyMap.put("remaining", rem.doubleValue());
                ccyMap.put("discrepancy", disc.doubleValue());
                ccyMap.put("tolerance", tol);
                ccyMap.put("withinTolerance", disc.abs().doubleValue() <= tol);
                currencies.add(ccyMap);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("statementId", statementId);
            result.put("isFinalConfirmation", isFinal);
            result.put("currencies", currencies);
            return result;

        } finally {
            JdbcHelper.closeQuietly(conn);
        }
    }

    // ── Step 11: Confirm ───────────────────────────────────────────────

    /**
     * Confirms records: validates, computes reconciliation, transitions to CONFIRMED.
     */
    public Map<String, Object> confirmRecords(String tableName, List<String> recordIds,
                                               boolean allowPartial,
                                               ValidationConfig config) throws SQLException {
        FormDataDao dao = getDao();
        ValidationConfig.ConfirmationConfig confConfig = config.getConfirmation();

        List<Map<String, Object>> confirmedList = new ArrayList<>();
        List<Map<String, Object>> skippedList = new ArrayList<>();
        List<Map<String, Object>> validationErrors = new ArrayList<>();
        List<String> validIds = new ArrayList<>();

        // Step 1: Filter by status=ready, validate
        for (String recordId : recordIds) {
            FormRow row = dao.load(null, tableName, recordId);
            if (row == null) {
                skippedList.add(Map.of("id", recordId, "reason", "Record not found"));
                continue;
            }

            String statusCode = row.getProperty("status");
            if (statusCode == null || !statusCode.equals(Status.READY.getCode())) {
                skippedList.add(Map.of("id", recordId,
                        "reason", "status is '" + (statusCode != null ? statusCode : "null") + "', not 'ready'"));
                continue;
            }

            // Step 2: Validate required fields
            List<String> errors = validateRecord(row, config);
            if (!errors.isEmpty()) {
                Map<String, Object> ve = new HashMap<>();
                ve.put("id", recordId);
                ve.put("errors", errors);
                validationErrors.add(ve);
                if (!allowPartial) {
                    // Fail entire batch
                    Map<String, Object> result = new HashMap<>();
                    result.put("confirmed", 0);
                    result.put("skipped", skippedList);
                    result.put("validationErrors", validationErrors);
                    return result;
                }
                continue;
            }

            validIds.add(recordId);
        }

        if (validIds.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("confirmed", 0);
            result.put("skipped", skippedList);
            result.put("validationErrors", validationErrors);
            return result;
        }

        // Step 3: Compute reconciliation for affected statements
        Map<String, Object> reconResult = null;
        if (config.getReconciliation() != null) {
            // Find the statement ID from the first valid record
            FormRow firstRow = dao.load(null, tableName, validIds.get(0));
            if (firstRow != null) {
                String stmtField = config.getReconciliation().getStatementField();
                String stmtId = firstRow.getProperty(stmtField);
                if (stmtId != null && !stmtId.isEmpty()) {
                    try {
                        reconResult = getReconciliation(tableName, stmtId, config);
                    } catch (Exception e) {
                        LogUtil.warn(CLASS_NAME, "Reconciliation computation failed: " + e.getMessage());
                    }
                }
            }
        }

        // Step 4: Transition via JDBC transaction
        Connection conn = null;
        try {
            conn = JdbcHelper.getConnection();
            conn.setAutoCommit(false);

            String now = Instant.now().toString();

            for (String recordId : validIds) {
                // Update status + confirmation fields
                Map<String, String> updates = new HashMap<>();
                updates.put("status", Status.CONFIRMED.getCode());
                if (confConfig != null) {
                    updates.put(confConfig.getConfirmedByField(), "enrichment-api");
                    updates.put(confConfig.getConfirmedAtField(), now);
                }
                JdbcHelper.updateColumns(conn, tableName, recordId, updates);

                // Audit
                JdbcHelper.insertAudit(conn, "ENRICHMENT", recordId,
                        Status.READY.getCode(), Status.CONFIRMED.getCode(),
                        "enrichment-api", "Confirmed for posting");

                Map<String, Object> item = new HashMap<>();
                item.put("id", recordId);
                item.put("previousStatus", Status.READY.getCode());
                item.put("newStatus", Status.CONFIRMED.getCode());
                confirmedList.add(item);
            }

            conn.commit();

        } catch (Exception e) {
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
            throw new SQLException("Confirmation failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
            JdbcHelper.closeQuietly(conn);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("confirmed", confirmedList.size());
        result.put("confirmedRecords", confirmedList);
        result.put("skipped", skippedList);
        result.put("validationErrors", validationErrors);
        if (reconResult != null) {
            result.put("reconciliation", reconResult);
        }
        return result;
    }

    // ── Step 12: Split ─────────────────────────────────────────────────

    /**
     * Splits a parent record into N child records with allocated amounts.
     */
    public Map<String, Object> splitRecord(String tableName, String id,
                                            List<Map<String, String>> allocations,
                                            ValidationConfig config)
            throws RecordNotFoundException, SQLException {

        ValidationConfig.SplitMergeConfig sm = config.getSplitMerge();
        if (sm == null) {
            throw new IllegalStateException("splitMerge config is not set");
        }

        // 1. Load parent via JDBC
        Connection conn = null;
        try {
            conn = JdbcHelper.getConnection();

            Map<String, String> parent = JdbcHelper.loadRowByFieldId(conn, tableName, id);
            if (parent == null) {
                throw new RecordNotFoundException(id);
            }

            // Validate status
            String statusCode = parent.get("status");
            if (statusCode != null) {
                Status status = Status.fromCode(statusCode);
                if (!SPLITTABLE_STATUSES.contains(status)) {
                    throw new IllegalArgumentException(
                            "Record status '" + statusCode + "' does not allow split. Allowed: enriched, adjusted, in_review, ready");
                }
            }

            // 2. Validate allocations
            if (allocations.size() < 2) {
                throw new IllegalArgumentException("Split requires at least 2 allocations");
            }

            BigDecimal parentAmount = parseBigDecimal(parent.get(sm.getAmountField()));
            BigDecimal parentFee = parseBigDecimal(parent.get(sm.getFeeField()));
            BigDecimal allocAmountSum = BigDecimal.ZERO;
            BigDecimal allocFeeSum = BigDecimal.ZERO;

            for (int i = 0; i < allocations.size(); i++) {
                Map<String, String> alloc = allocations.get(i);
                String customer = alloc.get(sm.getCustomerField());
                if (customer == null || customer.isEmpty()) {
                    throw new IllegalArgumentException("Allocation " + (i + 1) + ": customer is required");
                }
                allocAmountSum = allocAmountSum.add(parseBigDecimal(alloc.get(sm.getAmountField())));
                allocFeeSum = allocFeeSum.add(parseBigDecimal(alloc.get(sm.getFeeField())));
            }

            // Check amount sum
            if (allocAmountSum.subtract(parentAmount).abs().compareTo(new BigDecimal("0.01")) > 0) {
                throw new IllegalArgumentException(
                        "Amounts do not sum to source. Parent: " + parentAmount + ", allocations: " + allocAmountSum
                                + ", remaining: " + parentAmount.subtract(allocAmountSum));
            }

            // Auto-adjust fee rounding on last allocation
            BigDecimal feeRemainder = parentFee.subtract(allocFeeSum);
            if (feeRemainder.compareTo(BigDecimal.ZERO) != 0) {
                Map<String, String> lastAlloc = allocations.get(allocations.size() - 1);
                BigDecimal lastFee = parseBigDecimal(lastAlloc.get(sm.getFeeField()));
                lastAlloc.put(sm.getFeeField(), lastFee.add(feeRemainder).toPlainString());
            }

            // 3. Transaction
            conn.setAutoCommit(false);

            String fxRateStr = parent.get(sm.getFxRateField());
            BigDecimal fxRate = parseBigDecimal(fxRateStr);

            List<Map<String, Object>> children = new ArrayList<>();

            for (int seq = 0; seq < allocations.size(); seq++) {
                Map<String, String> alloc = allocations.get(seq);
                String childId = id + "-S" + (seq + 1);

                // Copy all parent fields to child
                Map<String, String> childFields = new HashMap<>(parent);
                // Remove standard columns that shouldn't be copied
                childFields.remove("id");
                childFields.remove("dateCreated");
                childFields.remove("dateModified");
                childFields.remove("createdBy");
                childFields.remove("modifiedBy");

                // Override from allocation
                BigDecimal amount = parseBigDecimal(alloc.get(sm.getAmountField()));
                BigDecimal fee = parseBigDecimal(alloc.get(sm.getFeeField()));
                BigDecimal total = amount.add(fee);
                BigDecimal eurAmount = fxRate.compareTo(BigDecimal.ZERO) != 0
                        ? total.multiply(fxRate).setScale(4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

                childFields.put(sm.getAmountField(), amount.toPlainString());
                childFields.put(sm.getFeeField(), fee.toPlainString());
                childFields.put(sm.getTotalField(), total.toPlainString());
                childFields.put(sm.getEurAmountField(), eurAmount.toPlainString());
                childFields.put(sm.getCustomerField(), alloc.get(sm.getCustomerField()));

                // Lineage
                childFields.put(sm.getOriginField(), "split");
                childFields.put(sm.getParentIdField(), id);
                childFields.put(sm.getGroupIdField(), id);
                childFields.put(sm.getSequenceField(), String.valueOf(seq + 1));
                childFields.put(sm.getLineageNoteField(),
                        "Split from " + id + ": allocation to " + alloc.get(sm.getCustomerField()));

                // Status: set to enriched (new child starts as enriched)
                childFields.put(sm.getStatusField(), Status.ENRICHED.getCode());

                // Reset version
                childFields.put("version", "0");

                JdbcHelper.insertRow(conn, tableName, childId, childFields);

                // Audit for child
                JdbcHelper.insertAudit(conn, "ENRICHMENT", childId,
                        "null", Status.ENRICHED.getCode(),
                        "enrichment-api", "Split child from " + id);

                Map<String, Object> childInfo = new LinkedHashMap<>();
                childInfo.put("id", childId);
                childInfo.put(sm.getCustomerField(), alloc.get(sm.getCustomerField()));
                childInfo.put(sm.getAmountField(), amount.doubleValue());
                childInfo.put(sm.getSequenceField(), seq + 1);
                children.add(childInfo);
            }

            // Supersede parent
            Map<String, String> parentUpdate = new HashMap<>();
            parentUpdate.put(sm.getStatusField(), Status.SUPERSEDED.getCode());
            JdbcHelper.updateColumns(conn, tableName, id, parentUpdate);

            JdbcHelper.insertAudit(conn, "ENRICHMENT", id,
                    statusCode, Status.SUPERSEDED.getCode(),
                    "enrichment-api", "Split into " + allocations.size() + " children");

            conn.commit();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("parentId", id);
            result.put("parentStatus", Status.SUPERSEDED.getCode());
            result.put("children", children);
            return result;

        } catch (RecordNotFoundException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
            throw new SQLException("Split failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
            JdbcHelper.closeQuietly(conn);
        }
    }

    // ── Step 13: Merge ─────────────────────────────────────────────────

    /**
     * Merges multiple source records into a single combined record.
     */
    public Map<String, Object> mergeRecords(String tableName, List<String> sourceIds,
                                             Map<String, String> mergedFields,
                                             ValidationConfig config)
            throws RecordNotFoundException, SQLException {

        ValidationConfig.SplitMergeConfig sm = config.getSplitMerge();
        if (sm == null) {
            throw new IllegalStateException("splitMerge config is not set");
        }

        if (sourceIds.size() < 2) {
            throw new IllegalArgumentException("Merge requires at least 2 records");
        }

        ValidationConfig.ReconciliationConfig recon = config.getReconciliation();

        Connection conn = null;
        try {
            conn = JdbcHelper.getConnection();

            // 1. Load all source records
            List<Map<String, String>> sources = new ArrayList<>();
            for (String srcId : sourceIds) {
                Map<String, String> row = JdbcHelper.loadRowByFieldId(conn, tableName, srcId);
                if (row == null) {
                    throw new RecordNotFoundException(srcId);
                }
                sources.add(row);
            }

            // 2. Validate eligibility
            String stmtField = recon != null ? recon.getStatementField() : "statement_id";
            String ccyField = recon != null ? recon.getCurrencyField() : "validated_currency";

            String firstStmt = sources.get(0).get(stmtField);
            String firstCcy = sources.get(0).get(ccyField);

            for (int i = 0; i < sources.size(); i++) {
                Map<String, String> src = sources.get(i);

                // Same statement
                String srcStmt = src.get(stmtField);
                if (firstStmt == null ? srcStmt != null : !firstStmt.equals(srcStmt)) {
                    throw new IllegalArgumentException("Cannot merge records from different statements");
                }

                // Same currency
                String srcCcy = src.get(ccyField);
                if (firstCcy == null ? srcCcy != null : !firstCcy.equals(srcCcy)) {
                    throw new IllegalArgumentException("Cannot merge records with different currencies");
                }

                // Status eligibility
                String statusCode = src.get("status");
                if (statusCode != null) {
                    Status status = Status.fromCode(statusCode);
                    if (!MERGEABLE_STATUSES.contains(status)) {
                        throw new IllegalArgumentException(
                                "Record " + sourceIds.get(i) + " has status '" + statusCode
                                        + "' — only enriched, adjusted, or in_review may be merged");
                    }
                }
            }

            // 3. Compute merged amounts
            BigDecimal totalAmount = BigDecimal.ZERO;
            BigDecimal totalFee = BigDecimal.ZERO;
            BigDecimal totalTotal = BigDecimal.ZERO;
            BigDecimal totalEur = BigDecimal.ZERO;

            for (Map<String, String> src : sources) {
                totalAmount = totalAmount.add(parseBigDecimal(src.get(sm.getAmountField())));
                totalFee = totalFee.add(parseBigDecimal(src.get(sm.getFeeField())));
                totalTotal = totalTotal.add(parseBigDecimal(src.get(sm.getTotalField())));
                totalEur = totalEur.add(parseBigDecimal(src.get(sm.getEurAmountField())));
            }

            // 4. Resolve non-amount fields — unanimous or blank
            Map<String, String> baseFields = new HashMap<>(sources.get(0));
            // Remove standard columns
            baseFields.remove("id");
            baseFields.remove("dateCreated");
            baseFields.remove("dateModified");
            baseFields.remove("createdBy");
            baseFields.remove("modifiedBy");

            // Check unanimity for each field
            for (String key : new ArrayList<>(baseFields.keySet())) {
                // Skip amount fields — these are computed
                if (key.equals(sm.getAmountField()) || key.equals(sm.getFeeField())
                        || key.equals(sm.getTotalField()) || key.equals(sm.getEurAmountField())) {
                    continue;
                }

                String firstVal = sources.get(0).get(key);
                boolean unanimous = true;
                for (int i = 1; i < sources.size(); i++) {
                    String val = sources.get(i).get(key);
                    if (firstVal == null ? val != null : !firstVal.equals(val)) {
                        unanimous = false;
                        break;
                    }
                }
                if (!unanimous) {
                    baseFields.put(key, "");
                }
            }

            // Set computed amounts
            baseFields.put(sm.getAmountField(), totalAmount.toPlainString());
            baseFields.put(sm.getFeeField(), totalFee.toPlainString());
            baseFields.put(sm.getTotalField(), totalTotal.toPlainString());
            baseFields.put(sm.getEurAmountField(), totalEur.toPlainString());

            // Apply mergedFields overrides
            if (mergedFields != null) {
                for (Map.Entry<String, String> entry : mergedFields.entrySet()) {
                    // Don't allow overriding computed amount fields
                    String key = entry.getKey();
                    if (!key.equals(sm.getAmountField()) && !key.equals(sm.getFeeField())
                            && !key.equals(sm.getTotalField()) && !key.equals(sm.getEurAmountField())) {
                        baseFields.put(key, entry.getValue());
                    }
                }
            }

            // 5. Validate required merge fields
            String internalType = baseFields.get("internal_type");
            String customerCode = baseFields.get(sm.getCustomerField());
            String debitCredit = baseFields.get("debit_credit");

            List<String> missingRequired = new ArrayList<>();
            if (internalType == null || internalType.isEmpty()) missingRequired.add("internal_type");
            if (customerCode == null || customerCode.isEmpty()) missingRequired.add(sm.getCustomerField());
            if (debitCredit == null || debitCredit.isEmpty()) missingRequired.add("debit_credit");

            if (!missingRequired.isEmpty()) {
                throw new IllegalArgumentException(
                        "Merge requires these fields to be set (provide in mergedFields if sources differ): "
                                + String.join(", ", missingRequired));
            }

            // 6. Transaction
            conn.setAutoCommit(false);

            String mergedId = UUID.randomUUID().toString();
            String groupId = UUID.randomUUID().toString();

            // Lineage fields
            baseFields.put(sm.getOriginField(), "merge");
            baseFields.put(sm.getGroupIdField(), groupId);
            baseFields.put(sm.getLineageNoteField(), "Merged from: " + String.join(", ", sourceIds));
            baseFields.put(sm.getStatusField(), Status.ENRICHED.getCode());
            baseFields.put("version", "0");

            JdbcHelper.insertRow(conn, tableName, mergedId, baseFields);

            // Audit for merged record
            JdbcHelper.insertAudit(conn, "ENRICHMENT", mergedId,
                    "null", Status.ENRICHED.getCode(),
                    "enrichment-api", "Merged from " + String.join(", ", sourceIds));

            // Supersede sources
            for (int i = 0; i < sourceIds.size(); i++) {
                String srcId = sourceIds.get(i);
                String srcStatus = sources.get(i).get("status");

                Map<String, String> srcUpdate = new HashMap<>();
                srcUpdate.put(sm.getStatusField(), Status.SUPERSEDED.getCode());
                srcUpdate.put(sm.getGroupIdField(), groupId);
                JdbcHelper.updateColumns(conn, tableName, srcId, srcUpdate);

                JdbcHelper.insertAudit(conn, "ENRICHMENT", srcId,
                        srcStatus != null ? srcStatus : "null", Status.SUPERSEDED.getCode(),
                        "enrichment-api", "Merged into " + mergedId);
            }

            conn.commit();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mergedId", mergedId);
            result.put("sourceIds", sourceIds);
            result.put(sm.getAmountField(), totalAmount.doubleValue());
            result.put(sm.getFeeField(), totalFee.doubleValue());
            result.put(sm.getTotalField(), totalTotal.doubleValue());
            return result;

        } catch (RecordNotFoundException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
            throw new SQLException("Merge failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
            JdbcHelper.closeQuietly(conn);
        }
    }

    // ── Validation helpers ─────────────────────────────────────────────

    /**
     * Validates a record against the ValidationConfig rules.
     * Returns list of error messages (empty if valid).
     */
    public List<String> validateRecord(FormRow row, ValidationConfig config) {
        List<String> errors = new ArrayList<>();

        // Check required fields
        for (String field : config.getRequiredFields()) {
            String val = row.getProperty(field);
            if (val == null || val.isEmpty()) {
                errors.add("Missing " + field);
            }
        }

        // Check conditional requirements
        for (ValidationConfig.ConditionalRequirement cr : config.getConditionalRequirements()) {
            String fieldValue = row.getProperty(cr.getConditionField());
            if (cr.matches(fieldValue)) {
                for (String reqField : cr.getRequiredFields()) {
                    String val = row.getProperty(reqField);
                    if (val == null || val.isEmpty()) {
                        errors.add("Missing " + reqField
                                + " (" + cr.getConditionField() + "=" + fieldValue + ")");
                    }
                }
            }
        }

        return errors;
    }

    // ── Private helpers ────────────────────────────────────────────────

    private void applyConfidenceOverrides(FormRow row, Set<String> changedFields,
                                          JSONArray overrides) {
        for (int i = 0; i < overrides.length(); i++) {
            JSONObject rule = overrides.optJSONObject(i);
            if (rule == null) continue;

            String triggerField = rule.optString("triggerField", "");
            if (triggerField.isEmpty() || !changedFields.contains(triggerField)) {
                continue;
            }

            JSONObject setFields = rule.optJSONObject("setFields");
            if (setFields != null) {
                Iterator<String> keys = setFields.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    row.setProperty(key, setFields.optString(key, ""));
                }
            }

            JSONArray clearFields = rule.optJSONArray("clearFields");
            if (clearFields != null) {
                for (int j = 0; j < clearFields.length(); j++) {
                    String fieldToClear = clearFields.optString(j, "");
                    if (!fieldToClear.isEmpty()) {
                        row.setProperty(fieldToClear, "");
                    }
                }
            }
        }
    }

    private int parseVersion(String versionStr) {
        if (versionStr == null || versionStr.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(versionStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private Map<String, BigDecimal> querySumByCurrency(Connection conn, String table,
                                                        String amtCol, String ccyCol,
                                                        String whereClause,
                                                        String[] params) throws SQLException {
        String sql = "SELECT " + ccyCol + " AS ccy, "
                + "SUM(CAST(" + amtCol + " AS DECIMAL(20,4))) AS amt "
                + "FROM " + table + " WHERE " + whereClause
                + " GROUP BY " + ccyCol;

        Map<String, BigDecimal> result = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ccy = rs.getString("ccy");
                    BigDecimal amt = rs.getBigDecimal("amt");
                    if (ccy != null && amt != null) {
                        result.put(ccy, amt);
                    }
                }
            }
        }
        return result;
    }

    private FormDataDao getDao() {
        return (FormDataDao) AppUtil.getApplicationContext().getBean("formDataDao");
    }
}
