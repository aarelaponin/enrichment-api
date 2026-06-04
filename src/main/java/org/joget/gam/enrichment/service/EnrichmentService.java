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
import org.joget.workflow.model.service.WorkflowUserManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

    private FormDataDao daoOverride;

    private static final Set<Status> DELETABLE_STATUSES =
            EnumSet.of(Status.NEW, Status.ERROR, Status.MANUAL_REVIEW);

    private static final Set<Status> MERGEABLE_STATUSES =
            EnumSet.of(Status.ENRICHED, Status.ADJUSTED, Status.IN_REVIEW);

    /** Fields that are editable in normal statuses (spec §4.3). */
    private static final Set<String> EDITABLE_FIELDS = Set.of(
            "transaction_date", "settlement_date", "debit_credit",
            "original_amount", "fee_amount", "total_amount", "description",
            "internal_type", "validated_currency", "fx_rate_to_eur",
            "fx_rate_date", "fx_rate_source", "requires_eur_parallel",
            "resolved_customer_id", "customer_code", "resolved_asset_id",
            "asset_category", "counterparty_id", "counterparty_short_code",
            "has_fee", "lineage_note", "processing_notes",
            // Workspace operations fields (WS-2)
            "matched_rule_id", "type_confidence",
            "customer_display_name", "customer_match_method",
            "base_amount_eur", "pair_id", "acc_post_id",
            "base_fee_eur", "loan_id", "loan_direction",
            "loan_resolution_method", "source_reference",
            "gl_debit_override", "gl_credit_override", "gl_override_reason",
            "fund_allocation_status", "period_locked"
    );

    /** Statuses where only processing_notes is editable. */
    private static final Set<Status> RESTRICTED_EDIT_STATUSES =
            EnumSet.of(Status.PROCESSING, Status.ERROR);

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

        // 2. Status check and field editability enforcement (spec §4.3)
        String statusCode = row.getProperty("status");
        Status currentStatus = null;
        boolean isTerminal = false;
        boolean isRestricted = false;

        if (statusCode != null && !statusCode.isEmpty()) {
            currentStatus = Status.fromCode(statusCode);
            isTerminal = (currentStatus == Status.CONFIRMED || currentStatus == Status.SUPERSEDED);
            isRestricted = RESTRICTED_EDIT_STATUSES.contains(currentStatus);
        }

        if (isTerminal || isRestricted) {
            // Terminal (CONFIRMED/SUPERSEDED) and restricted (PROCESSING/ERROR):
            // only processing_notes is editable
            fieldsToUpdate.keySet().retainAll(Set.of("processing_notes"));
            if (fieldsToUpdate.isEmpty()) {
                throw new TerminalStatusException(id, currentStatus);
            }
        } else {
            // Normal statuses: filter to only EDITABLE_FIELDS (silently drop non-editable)
            fieldsToUpdate.keySet().retainAll(EDITABLE_FIELDS);
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

        // 5. Confidence overrides — skip for terminal/restricted statuses
        if (confidenceOverrides != null && !isTerminal && !isRestricted) {
            applyConfidenceOverrides(row, changedFields, confidenceOverrides);
        }

        // 6. Validate auto-status transition before saving — skip for terminal/restricted
        boolean needsAutoTransition = !isTerminal && !isRestricted
                && !changedFields.isEmpty()
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

        // 8. Auto-status transition via StatusManager — skip for terminal/restricted
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
            String user = getCurrentUser();

            for (String recordId : validIds) {
                // Validate and write status transition (READY → CONFIRMED)
                transitionJdbc(conn, tableName, recordId,
                        Status.READY, Status.CONFIRMED, user, "Confirmed for posting");

                // Set confirmation metadata fields
                if (confConfig != null) {
                    Map<String, String> confUpdates = new HashMap<>();
                    confUpdates.put(confConfig.getConfirmedByField(), user);
                    confUpdates.put(confConfig.getConfirmedAtField(), now);
                    JdbcHelper.updateColumns(conn, tableName, recordId, confUpdates, user);
                }

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

            // Validate status — must be able to transition to SUPERSEDED
            String statusCode = parent.get("status");
            Status parentStatus = null;
            if (statusCode != null) {
                parentStatus = Status.fromCode(statusCode);
                if (!StatusManager.canTransition(EntityType.ENRICHMENT, parentStatus, Status.SUPERSEDED)) {
                    throw new IllegalArgumentException(
                            "Record status '" + statusCode + "' does not allow split");
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

            String user = getCurrentUser();
            String fxRateStr = parent.get(sm.getFxRateField());
            BigDecimal fxRate = parseBigDecimal(fxRateStr);
            String groupId = UUID.randomUUID().toString();

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

                // Per-child field overrides: apply any additional allocation keys
                // that are in EDITABLE_FIELDS but not already handled above
                for (Map.Entry<String, String> entry : alloc.entrySet()) {
                    String key = entry.getKey();
                    if (key.equals(sm.getAmountField()) || key.equals(sm.getFeeField())
                            || key.equals(sm.getCustomerField())) {
                        continue; // already handled
                    }
                    if (EDITABLE_FIELDS.contains(key)) {
                        childFields.put(key, entry.getValue());
                    }
                }

                // Lineage
                childFields.put(sm.getOriginField(), "split");
                childFields.put(sm.getParentIdField(), id);
                childFields.put(sm.getGroupIdField(), groupId);
                childFields.put(sm.getSequenceField(), String.valueOf(seq + 1));
                childFields.put(sm.getLineageNoteField(),
                        "Split from " + id + ": allocation to " + alloc.get(sm.getCustomerField()));

                // Status: set to enriched (new child starts as enriched)
                childFields.put(sm.getStatusField(), Status.ENRICHED.getCode());

                // Reset version
                childFields.put("version", "0");

                JdbcHelper.insertRow(conn, tableName, childId, childFields, user);

                // Audit for child (new record, not a transition)
                JdbcHelper.insertAudit(conn, EntityType.ENRICHMENT, childId,
                        "null", Status.ENRICHED.getCode(),
                        user, "Split child from " + id);

                Map<String, Object> childInfo = new LinkedHashMap<>();
                childInfo.put("id", childId);
                childInfo.put(sm.getCustomerField(), alloc.get(sm.getCustomerField()));
                childInfo.put(sm.getAmountField(), amount.doubleValue());
                childInfo.put(sm.getSequenceField(), seq + 1);
                children.add(childInfo);
            }

            // Supersede parent — validated against state machine
            transitionJdbc(conn, tableName, id, parentStatus, Status.SUPERSEDED,
                    user, "Split into " + allocations.size() + " children");

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

            String user = getCurrentUser();
            String mergedId = UUID.randomUUID().toString();
            String groupId = UUID.randomUUID().toString();

            // Lineage fields
            baseFields.put(sm.getOriginField(), "merge");
            baseFields.put(sm.getGroupIdField(), groupId);
            baseFields.put(sm.getLineageNoteField(), "Merged from: " + String.join(", ", sourceIds));
            baseFields.put(sm.getStatusField(), Status.ENRICHED.getCode());
            baseFields.put("version", "0");

            JdbcHelper.insertRow(conn, tableName, mergedId, baseFields, user);

            // Audit for merged record (new record, not a transition)
            JdbcHelper.insertAudit(conn, EntityType.ENRICHMENT, mergedId,
                    "null", Status.ENRICHED.getCode(),
                    user, "Merged from " + String.join(", ", sourceIds));

            // Supersede sources
            for (int i = 0; i < sourceIds.size(); i++) {
                String srcId = sourceIds.get(i);
                Status srcStatus = Status.fromCode(sources.get(i).get("status"));

                // Validate and write status transition via state machine
                transitionJdbc(conn, tableName, srcId, srcStatus, Status.SUPERSEDED,
                        user, "Merged into " + mergedId);

                // Also update group_id on source
                Map<String, String> grpUpdate = new HashMap<>();
                grpUpdate.put(sm.getGroupIdField(), groupId);
                JdbcHelper.updateColumns(conn, tableName, srcId, grpUpdate, user);
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

    // ── Create record ─────────────────────────────────────────────────

    /** Mandatory fields for record creation. */
    private static final Set<String> CREATE_REQUIRED_FIELDS = Set.of(
            "internal_type", "debit_credit", "total_amount",
            "validated_currency", "transaction_date", "statement_id"
    );

    /** Fields that must not be set by the caller on create. */
    private static final Set<String> CREATE_PROTECTED_FIELDS = Set.of(
            "id", "status", "version", "origin", "parent_enrichment_id",
            "group_id", "split_sequence", "confirmed_by", "confirmed_at"
    );

    /**
     * Creates a new enrichment record via JDBC.
     *
     * @param tableName the form table name
     * @param fields    field values (form element IDs, no c_ prefix)
     * @return result map with created record id and fields
     * @throws IllegalArgumentException if mandatory fields are missing
     * @throws SQLException on database error
     */
    public Map<String, Object> createRecord(String tableName, Map<String, String> fields)
            throws SQLException {

        // 1. Validate mandatory fields
        List<String> missing = new ArrayList<>();
        for (String req : CREATE_REQUIRED_FIELDS) {
            String val = fields.get(req);
            if (val == null || val.isEmpty()) {
                missing.add(req);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing required fields for create: " + String.join(", ", missing));
        }

        // 2. Strip protected fields — caller cannot set these
        for (String prot : CREATE_PROTECTED_FIELDS) {
            fields.remove(prot);
        }

        // 3. Filter to editable fields only (plus fields we set ourselves)
        fields.keySet().retainAll(EDITABLE_FIELDS);

        // 4. Set system fields
        fields.put("source_tp", "manual");
        fields.put("status", Status.ENRICHED.getCode());
        fields.put("version", "0");

        String id = UUID.randomUUID().toString();
        String user = getCurrentUser();

        Connection conn = null;
        try {
            conn = JdbcHelper.getConnection();

            JdbcHelper.insertRow(conn, tableName, id, fields, user);

            // Audit
            JdbcHelper.insertAudit(conn, EntityType.ENRICHMENT, id,
                    "null", Status.ENRICHED.getCode(),
                    user, "Created via API (manual)");

            Map<String, Object> result = new LinkedHashMap<>();
            // Echo back stored fields first
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                result.put(entry.getKey(), entry.getValue());
            }
            // Then set typed system fields (overwrite string versions)
            result.put("id", id);
            result.put("status", Status.ENRICHED.getCode());
            result.put("version", 0);
            return result;

        } catch (Exception e) {
            throw new SQLException("Create record failed: " + e.getMessage(), e);
        } finally {
            JdbcHelper.closeQuietly(conn);
        }
    }

    // ── Trade Allocation (D1) ────────────────────────────────────────────

    /**
     * Split a total quantity across customers in proportion to a basis — capital share for a
     * BUY (new lots), current holdings for a SELL (you can only sell what you hold). Uses
     * largest-remainder rounding to {@code scale} so the parts sum exactly to {@code total};
     * only positive-basis customers are included and the remainder goes to the largest-basis
     * customer (deterministic, tie-broken by customer id). Pure function — no database — so it
     * is unit-tested directly and the DB orchestration around it stays thin.
     */
    static java.util.LinkedHashMap<String, BigDecimal> splitByShare(
            java.util.LinkedHashMap<String, BigDecimal> basisByCustomer, BigDecimal total, int scale) {
        java.util.LinkedHashMap<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        if (total == null || total.signum() <= 0) return out;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal b : basisByCustomer.values()) {
            if (b != null && b.signum() > 0) sum = sum.add(b);
        }
        if (sum.signum() <= 0) return out;
        BigDecimal scaledTotal = total.setScale(scale, RoundingMode.HALF_UP);
        BigDecimal allocated = BigDecimal.ZERO;
        String largest = null;
        BigDecimal largestBasis = BigDecimal.valueOf(-1);
        for (Map.Entry<String, BigDecimal> e : basisByCustomer.entrySet()) {
            BigDecimal b = e.getValue();
            if (b == null || b.signum() <= 0) continue;
            BigDecimal share = b.divide(sum, 12, RoundingMode.HALF_UP);
            BigDecimal q = scaledTotal.multiply(share).setScale(scale, RoundingMode.HALF_UP);
            out.put(e.getKey(), q);
            allocated = allocated.add(q);
            if (b.compareTo(largestBasis) > 0
                    || (b.compareTo(largestBasis) == 0 && (largest == null || e.getKey().compareTo(largest) < 0))) {
                largestBasis = b;
                largest = e.getKey();
            }
        }
        BigDecimal remainder = scaledTotal.subtract(allocated);
        if (remainder.signum() != 0 && largest != null) {
            out.put(largest, out.get(largest).add(remainder));
        }
        return out;
    }

    /**
     * Manual allocation (the directed override) — records allocationMethod=MANUAL.
     */
    public Map<String, Object> allocateTrade(
            String enrichmentTable,
            String enrichmentId,
            String customerId,
            BigDecimal quantity,
            ValidationConfig config) throws Exception {
        return allocateTrade(enrichmentTable, enrichmentId, customerId, quantity, config, "MANUAL");
    }

    /**
     * Allocate a portion of a pooled securities trade to a single customer.
     * Creates F03.02 lot, upserts F03.01 position, upserts F03.00 portfolio,
     * updates F01.05 allocation status. The lot is tagged with {@code allocationMethod}
     * (MANUAL for the directed override, AUTO_CAPITAL_SHARE for the automatic path) so the
     * audit trail distinguishes how each lot was created.
     */
    public Map<String, Object> allocateTrade(
            String enrichmentTable,
            String enrichmentId,
            String customerId,
            BigDecimal quantity,
            ValidationConfig config,
            String allocationMethod) throws Exception {

        ValidationConfig.AllocationConfig ac = config.getAllocation();
        Connection conn = null;
        boolean committed = false;

        try {
            conn = JdbcHelper.getConnection();
            conn.setAutoCommit(false);

            // === PHASE 1: LOAD & VALIDATE ===

            // V1: Load enrichment record
            Map<String, String> enrichment = JdbcHelper.loadRowByFieldId(conn, enrichmentTable, enrichmentId);
            if (enrichment == null) {
                throw new RecordNotFoundException(enrichmentId);
            }

            // Row-level lock for concurrency safety
            String lockSql = "SELECT id FROM " + JdbcHelper.dbTable(enrichmentTable)
                    + " WHERE id = ? FOR UPDATE";
            try (PreparedStatement ps = conn.prepareStatement(lockSql)) {
                ps.setString(1, enrichmentId);
                ps.executeQuery();
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
            BigDecimal secuQty = parseBigDecimal(secu.get(ac.getSecuQuantityField())).abs();
            BigDecimal secuPrice = parseBigDecimal(secu.get(ac.getSecuPriceField()));
            BigDecimal secuFee = parseBigDecimal(secu.get(ac.getSecuFeeField())).abs();
            String secuCurrency = secu.get(ac.getSecuCurrencyField());
            String secuTicker = secu.get(ac.getSecuTickerField());

            if (secuQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Securities transaction has no valid quantity");
            }
            if (secuPrice.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Securities transaction has no valid price");
            }

            // V5a: Full pairing gate
            String pairId = enrichment.get("pair_id");
            String hasFee = enrichment.get("has_fee");
            if ("yes".equalsIgnoreCase(hasFee)) {
                String feeTrxId = enrichment.get("fee_trx_id");
                if (pairId == null || pairId.isEmpty()
                        || feeTrxId == null || feeTrxId.isEmpty()) {
                    throw new IllegalArgumentException(
                        "Securities transaction requires full pairing (principal + fee) "
                        + "before allocation. The fee bank transaction has not yet been matched.");
                }
            } else {
                if (pairId == null || pairId.isEmpty()) {
                    throw new IllegalArgumentException(
                        "Securities transaction must be paired with bank settlement before allocation.");
                }
            }

            // V6: Load and validate customer
            Map<String, String> customer = JdbcHelper.loadRowByField(conn, ac.getCustomerTable(), "customerId", customerId);
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
            String assetId = enrichment.get(ac.getEnrichmentAssetField());
            Map<String, String> existingPosition = null;
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
                if (quantity.compareTo(positionQty) > 0) {
                    throw new IllegalArgumentException(
                            "Customer has insufficient holdings (" + positionQty + " < " + quantity + ")");
                }
            }

            // === PHASE 2: COMPUTE ===

            BigDecimal totalAmount = quantity.multiply(secuPrice).setScale(6, RoundingMode.HALF_UP);
            BigDecimal feeAmount = secuFee.multiply(quantity).divide(secuQty, 6, RoundingMode.HALF_UP);
            BigDecimal totalCostWithFees = totalAmount.add(feeAmount).setScale(6, RoundingMode.HALF_UP);
            String allocationDate = enrichment.get(ac.getEnrichmentTrxDateField());
            String assetIsin = enrichment.get(ac.getEnrichmentAssetIsinField());

            BigDecimal fxRate = parseBigDecimal(enrichment.get("fx_rate_to_eur"));
            if (fxRate.compareTo(BigDecimal.ZERO) <= 0) fxRate = BigDecimal.ONE;

            BigDecimal totalAmountEur = totalAmount.multiply(fxRate).setScale(6, RoundingMode.HALF_UP);
            BigDecimal feeAmountEur = feeAmount.multiply(fxRate).setScale(6, RoundingMode.HALF_UP);
            BigDecimal totalCostWithFeesEur = totalCostWithFees.multiply(fxRate).setScale(6, RoundingMode.HALF_UP);

            // For SELL: compute cost basis and realized P&L
            BigDecimal costBasisUsed = BigDecimal.ZERO;
            BigDecimal realizedPnl = BigDecimal.ZERO;
            String costBasisMethod = "AVERAGE";

            if (isSell && existingPosition != null) {
                BigDecimal posTotalCost = parseBigDecimal(existingPosition.get("totalCostBasis"));
                BigDecimal posQty = parseBigDecimal(existingPosition.get("quantity"));
                if (posQty.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal avgCost = posTotalCost.divide(posQty, 6, RoundingMode.HALF_UP);
                    costBasisUsed = avgCost.multiply(quantity).setScale(6, RoundingMode.HALF_UP);
                    realizedPnl = totalAmount.subtract(costBasisUsed).setScale(6, RoundingMode.HALF_UP);
                }
            }

            // Generate IDs
            String lotId = generateId(ac.getLotIdFormat(), ac.getLotIdEnvVar());

            // === PHASE 3: WRITE (within transaction) ===

            String username = getCurrentUser();

            // Step 1: Resolve/Create F03.01 portfolioPosition
            boolean positionCreated = false;
            String positionId;

            if (existingPosition != null) {
                positionId = existingPosition.get("id");
            } else if (isBuy) {
                positionCreated = true;
                positionId = generateId(ac.getPositionIdFormat(), ac.getPositionIdEnvVar());
                Map<String, String> posFields = new LinkedHashMap<>();
                posFields.put("customerId", customerId);
                posFields.put("customerDisplayName", customerName != null ? customerName : "");
                posFields.put("assetId", assetId);
                posFields.put("assetTicker", secuTicker != null ? secuTicker : "");
                posFields.put("assetIsin", assetIsin != null ? assetIsin : "");
                posFields.put("quantity", "0");
                posFields.put("totalCostBasis", "0");
                posFields.put("totalCostBasisEur", "0");
                posFields.put("currency", secuCurrency != null ? secuCurrency : "");
                posFields.put("firstAcquisitionDate", allocationDate != null ? allocationDate : "");
                posFields.put("lastTransactionDate", allocationDate != null ? allocationDate : "");
                posFields.put("status", "active");
                posFields.put("positionId", positionId);
                JdbcHelper.insertRow(conn, ac.getPositionTable(), positionId, posFields, username);

                existingPosition = JdbcHelper.loadRowByFieldId(conn, ac.getPositionTable(), positionId);
            } else {
                throw new IllegalStateException("Cannot create position for SELL");
            }

            // Step 2: Insert F03.02 allocationLot
            Map<String, String> lotFields = new LinkedHashMap<>();
            lotFields.put("sourceEnrichmentId", enrichmentId);
            lotFields.put("positionId", positionId);
            lotFields.put("customerId", customerId);
            lotFields.put("assetId", assetId != null ? assetId : "");
            lotFields.put("assetTicker", secuTicker != null ? secuTicker : "");
            lotFields.put("direction", direction);
            lotFields.put("quantity", quantity.toPlainString());
            lotFields.put("pricePerUnit", secuPrice.toPlainString());
            lotFields.put("totalAmount", totalAmount.toPlainString());
            lotFields.put("feeAmount", feeAmount.toPlainString());
            lotFields.put("totalCostWithFees", totalCostWithFees.toPlainString());
            lotFields.put("currency", secuCurrency != null ? secuCurrency : "");
            lotFields.put("allocationDate", allocationDate != null ? allocationDate : "");
            lotFields.put("allocationMethod", allocationMethod == null || allocationMethod.isEmpty() ? "MANUAL" : allocationMethod);
            lotFields.put("remainingQuantity", isBuy ? quantity.toPlainString() : "0");
            lotFields.put("costBasisMethod", costBasisMethod);
            lotFields.put("totalAmountEur", totalAmountEur.toPlainString());
            lotFields.put("feeAmountEur", feeAmountEur.toPlainString());
            if (isSell) {
                BigDecimal costBasisPerUnit = parseBigDecimal(
                        existingPosition != null ? existingPosition.get("averageCostPerUnit") : "0");
                lotFields.put("costBasisPerUnit", costBasisPerUnit.toPlainString());
                lotFields.put("totalCostBasis", costBasisUsed.toPlainString());
                lotFields.put("realizedPnl", realizedPnl.toPlainString());
                lotFields.put("realizedPnlEur", realizedPnl.multiply(fxRate).setScale(6, RoundingMode.HALF_UP).toPlainString());
                lotFields.put("consumedLotIds", "");
            }
            lotFields.put("lotId", lotId);
            // The lot is persisted through Joget's FormDataDao AFTER the commit below (not via
            // raw JDBC here): form data written outside Joget is NOT returned by FormDataDao
            // reads, so a raw-JDBC lot is invisible to the GL journalizer. See post-commit write.

            // Step 3: Update F03.01 portfolioPosition
            BigDecimal posQtyBefore = parseBigDecimal(existingPosition.get("quantity"));
            BigDecimal posCostBefore = parseBigDecimal(existingPosition.get("totalCostBasis"));

            BigDecimal newQty, newCost;
            String newStatus = "active";

            if (isBuy) {
                newQty = posQtyBefore.add(quantity);
                newCost = posCostBefore.add(totalCostWithFees).setScale(6, RoundingMode.HALF_UP);
            } else {
                newQty = posQtyBefore.subtract(quantity);
                newCost = posCostBefore.subtract(costBasisUsed).setScale(6, RoundingMode.HALF_UP);
                if (newQty.compareTo(BigDecimal.ZERO) <= 0) {
                    newQty = BigDecimal.ZERO;
                    newCost = BigDecimal.ZERO;
                    newStatus = "closed";
                }
            }

            BigDecimal avgCostPerUnit = newQty.compareTo(BigDecimal.ZERO) > 0
                    ? newCost.divide(newQty, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            Map<String, String> posUpdate = new LinkedHashMap<>();
            posUpdate.put("quantity", newQty.toPlainString());
            posUpdate.put("totalCostBasis", newCost.toPlainString());

            BigDecimal posCostEurBefore = parseBigDecimal(existingPosition.get("totalCostBasisEur"));
            BigDecimal newCostEur;
            if (isBuy) {
                newCostEur = posCostEurBefore.add(totalCostWithFeesEur).setScale(6, RoundingMode.HALF_UP);
            } else {
                newCostEur = posCostEurBefore.subtract(costBasisUsed.multiply(fxRate).setScale(6, RoundingMode.HALF_UP)).setScale(6, RoundingMode.HALF_UP);
                if (newQty.compareTo(BigDecimal.ZERO) <= 0) {
                    newCostEur = BigDecimal.ZERO;
                }
            }
            posUpdate.put("totalCostBasisEur", newCostEur.toPlainString());

            posUpdate.put("lastTransactionDate", allocationDate != null ? allocationDate : "");
            posUpdate.put("status", newStatus);
            posUpdate.put("averageCostPerUnit", avgCostPerUnit.toPlainString());
            JdbcHelper.updateColumns(conn, ac.getPositionTable(), positionId, posUpdate, username);

            // Step 4: Upsert F03.00 customerPortfolio
            boolean portfolioCreated = false;
            String portfolioId;

            Map<String, String> existingPortfolio = JdbcHelper.loadRowByField(
                    conn, ac.getPortfolioTable(), "customerId", customerId);

            if (existingPortfolio == null) {
                portfolioCreated = true;
                portfolioId = generateId(ac.getPortfolioIdFormat(), ac.getPortfolioIdEnvVar());
                Map<String, String> pfFields = new LinkedHashMap<>();
                pfFields.put("customerId", customerId);
                pfFields.put("customerDisplayName", customerName != null ? customerName : "");
                pfFields.put("positionCount", "1");
                pfFields.put("totalCostBasis", totalCostWithFeesEur.toPlainString());
                pfFields.put("totalMarketValue", "");
                pfFields.put("totalUnrealizedPnl", "");
                pfFields.put("totalRealizedPnl", "0");
                pfFields.put("currency", "EUR");
                // snapshotDate / lastRefreshedAt are not columns on customerPortfolio
                // (the form has no such fields). Joget's built-in dateModified already
                // records the last update, so we do not write a redundant timestamp.
                pfFields.put("status", "active");
                pfFields.put("portfolioId", portfolioId);
                JdbcHelper.insertRow(conn, ac.getPortfolioTable(), portfolioId, pfFields, username);
            } else {
                portfolioId = existingPortfolio.get("id");
            }

            // Recalculate portfolio aggregates
            int activePositions = countActivePositions(conn, ac.getPositionTable(), customerId);
            BigDecimal totalPortfolioCost = sumPositionCostsEur(conn, ac.getPositionTable(), customerId);

            Map<String, String> pfUpdate = new LinkedHashMap<>();
            pfUpdate.put("positionCount", String.valueOf(activePositions));
            pfUpdate.put("totalCostBasis", totalPortfolioCost.toPlainString());
            if (activePositions == 0) {
                pfUpdate.put("status", "closed");
            }
            JdbcHelper.updateColumns(conn, ac.getPortfolioTable(), portfolioId, pfUpdate, username);

            // Step 5: Update F01.05 enrichment record
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

            conn.commit();
            committed = true;

            // Persist the allocation lot through Joget's FormDataDao (platform-native write) so it
            // is immediately visible to every FormDataDao reader — including the GL journalizer.
            // Written post-commit: the positionId it references is already durable, and the lot is
            // the only allocation record a Joget reader consumes (positions/portfolio are read back
            // through this same service).
            FormRow lotRow = new FormRow();
            lotRow.setId(lotId);
            for (Map.Entry<String, String> lf : lotFields.entrySet()) {
                lotRow.setProperty(lf.getKey(), lf.getValue());
            }
            FormRowSet lotSet = new FormRowSet();
            lotSet.add(lotRow);
            getDao().saveOrUpdate(null, ac.getLotTable(), lotSet);

            // === PHASE 4: BUILD RESPONSE ===

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
            result.put("totalAmountEur", totalAmountEur.doubleValue());
            result.put("feeAmountEur", feeAmountEur.doubleValue());
            result.put("totalCostWithFeesEur", totalCostWithFeesEur.doubleValue());
            result.put("fxRate", fxRate.doubleValue());
            result.put("allocationStatus", newAllocStatus);
            result.put("remainingQty", secuQty.subtract(newAllocatedQty).doubleValue());
            if (isSell) {
                result.put("costBasisUsed", costBasisUsed.doubleValue());
                result.put("realizedPnl", realizedPnl.doubleValue());
                result.put("costBasisMethod", costBasisMethod);
            }
            return result;

        } catch (RecordNotFoundException | IllegalArgumentException | IllegalStateException e) {
            if (conn != null && !committed) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
            throw e;
        } catch (Exception e) {
            if (conn != null && !committed) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
            throw new SQLException("Allocation failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
            JdbcHelper.closeQuietly(conn);
        }
    }

    /**
     * Automatically allocate a whole pooled trade across investors — BUY by capital share (as of
     * the trade date), SELL by current holdings (you can only sell what you hold) — by computing
     * each investor's quantity and reusing {@link #allocateTrade} per investor, so all lot /
     * position / cost-basis mechanics are shared (single source of truth). The manual modal stays
     * the audited override. Returns a summary; no-product / no-capital / no-holders are reported,
     * not silently dropped.
     */
    public Map<String, Object> autoAllocateTrade(String enrichmentTable, String enrichmentId,
                                                 ValidationConfig config) throws Exception {
        ValidationConfig.AllocationConfig ac = config.getAllocation();
        ValidationConfig.CapitalAllocationConfig cc = config.getCapitalAllocation();

        String direction = "";
        java.util.LinkedHashMap<String, BigDecimal> shares = null;
        Connection conn = null;
        try {
            conn = JdbcHelper.getConnection();
            Map<String, String> en = JdbcHelper.loadRowByFieldId(conn, enrichmentTable, enrichmentId);
            if (en == null) throw new RecordNotFoundException(enrichmentId);

            String internalType = en.get(ac.getEnrichmentTypeField());
            if (!ac.getEligibleTypes().contains(internalType)) {
                throw new IllegalArgumentException("Record type '" + internalType + "' is not eligible for allocation");
            }
            String status = en.get(ac.getEnrichmentStatusField());
            if (!ac.getEligibleStatuses().contains(status)) {
                throw new IllegalArgumentException("Record status '" + status + "' is not eligible for allocation");
            }
            boolean isSell = ac.isSellType(internalType);
            direction = isSell ? "SELL" : "BUY";

            if ("allocated".equals(en.get(ac.getEnrichmentAllocStatusField()))) {
                return autoSummary(enrichmentId, "already_allocated", direction, 0, java.util.Collections.emptyMap());
            }

            String assetId = en.get(ac.getEnrichmentAssetField());
            String tradeDate = en.get(ac.getEnrichmentTrxDateField());

            // A securities allocation must have an asset. Asset-less rows (e.g. SEC_* cash /
            // settlement legs) are reported, not turned into asset-less lots.
            if (assetId == null || assetId.trim().isEmpty()) {
                return autoSummary(enrichmentId, "no_asset", direction, 0, java.util.Collections.emptyMap());
            }

            String sourceId = en.get(ac.getEnrichmentSourceField());
            if (sourceId == null || sourceId.isEmpty()) throw new IllegalArgumentException("Enrichment has no linked securities transaction");
            Map<String, String> secu = JdbcHelper.loadRowByFieldId(conn, ac.getSecuTable(), sourceId);
            if (secu == null) throw new IllegalArgumentException("Securities transaction not found: " + sourceId);
            BigDecimal secuQty = parseBigDecimal(secu.get(ac.getSecuQuantityField())).abs();
            BigDecimal already = JdbcHelper.sumColumn(conn, ac.getLotTable(), "quantity", "sourceEnrichmentId", enrichmentId);
            BigDecimal remaining = secuQty.subtract(already);
            if (remaining.signum() <= 0) {
                return autoSummary(enrichmentId, "nothing_remaining", direction, 0, java.util.Collections.emptyMap());
            }

            java.util.LinkedHashMap<String, BigDecimal> basis = isSell
                    ? holdingsBasis(conn, ac, assetId)
                    : capitalBasis(conn, cc, tradeDate);
            shares = splitByShare(basis, remaining, cc.getShareScale());
            if (shares.isEmpty()) {
                return autoSummary(enrichmentId, isSell ? "no_holders" : "no_capital_basis", direction, 0, java.util.Collections.emptyMap());
            }
        } finally {
            JdbcHelper.closeQuietly(conn);
        }

        // allocateTrade opens its own transaction; loop after closing the read connection.
        int allocated = 0;
        Map<String, Object> perCustomer = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : shares.entrySet()) {
            BigDecimal qty = e.getValue();
            if (qty == null || qty.signum() <= 0) continue;
            try {
                allocateTrade(enrichmentTable, enrichmentId, e.getKey(), qty, config, "AUTO_CAPITAL_SHARE");
                perCustomer.put(e.getKey(), qty.toPlainString());
                allocated++;
            } catch (Exception ex) {
                perCustomer.put(e.getKey(), "ERROR: " + ex.getMessage());
            }
        }
        return autoSummary(enrichmentId, allocated > 0 ? "allocated" : "failed", direction, allocated, perCustomer);
    }

    private static Map<String, Object> autoSummary(String enrichmentId, String outcome, String direction,
                                                   int allocated, Map<String, Object> perCustomer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enrichmentId", enrichmentId);
        m.put("outcome", outcome);
        m.put("direction", direction);
        m.put("investorsAllocated", allocated);
        m.put("perCustomer", perCustomer);
        return m;
    }

    /** Auto-allocate every eligible, not-yet-allocated trade. Returns an aggregate summary. */
    public Map<String, Object> autoAllocateAllTrades(String enrichmentTable, ValidationConfig config) throws Exception {
        ValidationConfig.AllocationConfig ac = config.getAllocation();
        List<String> ids = new ArrayList<>();
        Connection conn = null;
        try {
            conn = JdbcHelper.getConnection();
            String sql = "SELECT id, " + JdbcHelper.dbCol(ac.getEnrichmentTypeField()) + " AS xtype, "
                    + JdbcHelper.dbCol(ac.getEnrichmentStatusField()) + " AS xstatus, "
                    + JdbcHelper.dbCol(ac.getEnrichmentAllocStatusField()) + " AS xalloc "
                    + "FROM " + JdbcHelper.dbTable(enrichmentTable);
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql);
                 java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String t = rs.getString("xtype");
                    String s = rs.getString("xstatus");
                    String a = rs.getString("xalloc");
                    if (ac.getEligibleTypes().contains(t) && ac.getEligibleStatuses().contains(s)
                            && !"allocated".equals(a)) {
                        ids.add(rs.getString("id"));
                    }
                }
            }
        } finally {
            JdbcHelper.closeQuietly(conn);
        }

        int tradesAllocated = 0, lotsCreated = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> r = autoAllocateTrade(enrichmentTable, id, config);
            details.add(r);
            Object n = r.get("investorsAllocated");
            if (n instanceof Integer && (Integer) n > 0) { tradesAllocated++; lotsCreated += (Integer) n; }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tradesScanned", ids.size());
        summary.put("tradesAllocated", tradesAllocated);
        summary.put("lotsCreated", lotsCreated);
        summary.put("details", details);
        return summary;
    }

    /** Capital basis: each active investor's capital contributions (deposits) up to {@code asOf}. */
    private java.util.LinkedHashMap<String, BigDecimal> capitalBasis(Connection conn,
            ValidationConfig.CapitalAllocationConfig cc, String asOf) throws SQLException {
        java.util.LinkedHashMap<String, BigDecimal> basis = new java.util.LinkedHashMap<>();
        // Resolve the single active investment product; if 0 or >1, return empty (caller reports).
        List<Map<String, String>> products = JdbcHelper.loadRowsByField(conn, cc.getProductTable(),
                cc.getProductBusinessLineField(), cc.getInvestmentBusinessLineValue());
        String productId = null;
        for (Map<String, String> p : products) {
            if (cc.getActiveStatusValue().equalsIgnoreCase(caNz(p.get(cc.getProductStatusField())))) {
                String pid = caNz(p.get(cc.getProductIdField()));
                if (pid.isEmpty()) continue;
                if (productId == null) productId = pid;
                else if (!productId.equals(pid)) return new java.util.LinkedHashMap<>(); // ambiguous
            }
        }
        if (productId == null) return basis;

        List<Map<String, String>> holdings = JdbcHelper.loadRowsByField(conn, cc.getHoldingTable(),
                cc.getHoldingProductField(), productId);
        for (Map<String, String> h : holdings) {
            if (!cc.getInvestorRoleValue().equalsIgnoreCase(caNz(h.get(cc.getHoldingRoleField())))) continue;
            if (!cc.getActiveStatusValue().equalsIgnoreCase(caNz(h.get(cc.getHoldingStatusField())))) continue;
            if (!caOnOrBefore(caNz(h.get(cc.getHoldingEffectiveFromField())), asOf)) continue;
            String cust = caNz(h.get(cc.getHoldingCustomerField()));
            if (cust.isEmpty()) continue;
            BigDecimal cap = capitalAsOf(conn, cc, cust, asOf);
            if (cap.signum() > 0) basis.put(cust, cap);
        }
        return basis;
    }

    private BigDecimal capitalAsOf(Connection conn, ValidationConfig.CapitalAllocationConfig cc,
                                   String customerId, String asOf) throws SQLException {
        BigDecimal sum = BigDecimal.ZERO;
        List<Map<String, String>> deposits = JdbcHelper.loadRowsByField(conn, cc.getDepositTable(),
                cc.getDepositCustomerField(), customerId);
        for (Map<String, String> d : deposits) {
            if (caOnOrBefore(caNz(d.get(cc.getDepositValueDateField())), asOf)) {
                sum = sum.add(parseBigDecimal(d.get(cc.getDepositAmountField())));
            }
        }
        return sum;
    }

    /** Holdings basis: each customer's current position quantity in the asset (SELL allocation). */
    private java.util.LinkedHashMap<String, BigDecimal> holdingsBasis(Connection conn,
            ValidationConfig.AllocationConfig ac, String assetId) throws SQLException {
        java.util.LinkedHashMap<String, BigDecimal> basis = new java.util.LinkedHashMap<>();
        if (assetId == null || assetId.isEmpty()) return basis;
        List<Map<String, String>> positions = JdbcHelper.loadRowsByField(conn, ac.getPositionTable(), "assetId", assetId);
        for (Map<String, String> p : positions) {
            String cust = caNz(p.get("customerId"));
            if (cust.isEmpty()) continue;
            BigDecimal qty = parseBigDecimal(p.get("quantity"));
            if (qty.signum() > 0) basis.merge(cust, qty, BigDecimal::add);
        }
        return basis;
    }

    private static String caNz(String s) { return s == null ? "" : s.trim(); }

    private static boolean caOnOrBefore(String a, String b) {
        if (a == null || a.isEmpty()) return true;
        if (b == null || b.isEmpty()) return true;
        return a.compareTo(b) <= 0;
    }

    // ── Income Allocation (D2) ──────────────────────────────────────────

    /**
     * Allocate income (dividend/interest) proportionally across customers
     * based on share-days (quantity held x days held) during the accrual period.
     *
     * @param enrichmentTable the enrichment form table name
     * @param enrichmentId    the enrichment record ID
     * @param periodStart     accrual period start (yyyy-MM-dd)
     * @param periodEnd       accrual period end (yyyy-MM-dd)
     * @param preview         if true, compute allocations without writing
     * @param config          validation config
     * @return result map with allocations
     */
    public Map<String, Object> allocateIncome(
            String enrichmentTable, String enrichmentId,
            String periodStart, String periodEnd,
            boolean preview,
            ValidationConfig config) throws Exception {

        ValidationConfig.IncomeAllocationConfig iac = config.getIncomeAllocation();
        Connection conn = null;
        boolean committed = false;

        try {
            conn = JdbcHelper.getConnection();
            conn.setAutoCommit(false);

            // === PHASE 1: LOAD & VALIDATE ===

            Map<String, String> enrichment = JdbcHelper.loadRowByFieldId(conn, enrichmentTable, enrichmentId);
            if (enrichment == null) {
                throw new RecordNotFoundException(enrichmentId);
            }

            if (!preview) {
                String lockSql = "SELECT id FROM " + JdbcHelper.dbTable(enrichmentTable)
                        + " WHERE id = ? FOR UPDATE";
                try (PreparedStatement ps = conn.prepareStatement(lockSql)) {
                    ps.setString(1, enrichmentId);
                    ps.executeQuery();
                }
            }

            String internalType = enrichment.get(iac.getEnrichmentTypeField());
            if (!iac.getEligibleTypes().contains(internalType)) {
                throw new IllegalArgumentException(
                        "Record type '" + internalType + "' is not eligible for income allocation");
            }

            String status = enrichment.get(iac.getEnrichmentStatusField());
            if (!iac.getEligibleStatuses().contains(status)) {
                throw new IllegalArgumentException(
                        "Record status '" + status + "' is not eligible for income allocation");
            }

            String allocStatus = enrichment.get(iac.getEnrichmentAllocStatusField());
            if ("allocated".equals(allocStatus)) {
                throw new IllegalStateException("Income is already allocated");
            }

            String assetId = enrichment.get(iac.getEnrichmentAssetField());
            if (assetId == null || assetId.isEmpty()) {
                throw new IllegalArgumentException("No asset linked to enrichment record");
            }

            LocalDate pStart = LocalDate.parse(periodStart);
            LocalDate pEnd = LocalDate.parse(periodEnd);
            if (!pEnd.isAfter(pStart)) {
                throw new IllegalArgumentException("Period end must be after period start");
            }

            BigDecimal totalAmount = parseBigDecimal(enrichment.get(iac.getEnrichmentAmountField()));
            String currency = enrichment.get(iac.getEnrichmentCurrencyField());
            BigDecimal fxRate = parseBigDecimal(enrichment.get(iac.getEnrichmentFxRateField()));
            if (fxRate.compareTo(BigDecimal.ZERO) <= 0) fxRate = BigDecimal.ONE;

            // Resolve asset ticker from lots (use first lot's ticker)
            String assetTicker = "";

            // === PHASE 2: RECONSTRUCT HOLDINGS ===

            List<Map<String, String>> allLots = JdbcHelper.loadRowsByField(
                    conn, iac.getLotTable(), iac.getLotAssetIdField(), assetId);

            // Resolve asset ticker from first lot
            if (!allLots.isEmpty()) {
                String t = getField(allLots.get(0), iac.getLotAssetTickerField());
                if (t != null && !t.isEmpty()) assetTicker = t;
            }

            // Group lots by customerId
            Map<String, List<Map<String, String>>> lotsByCustomer = new LinkedHashMap<>();
            for (Map<String, String> lot : allLots) {
                String cid = getField(lot, iac.getLotCustomerIdField());
                if (cid != null && !cid.isEmpty()) {
                    lotsByCustomer.computeIfAbsent(cid, k -> new ArrayList<>()).add(lot);
                }
            }

            // Compute share-days per customer
            Map<String, BigDecimal> customerShareDays = new LinkedHashMap<>();
            Map<String, Long> customerHoldingDays = new LinkedHashMap<>();
            Map<String, BigDecimal> customerAvgQty = new LinkedHashMap<>();

            for (Map.Entry<String, List<Map<String, String>>> entry : lotsByCustomer.entrySet()) {
                String customerId = entry.getKey();
                List<Map<String, String>> custLots = entry.getValue();

                // Build daily delta map: date -> quantity change
                TreeMap<LocalDate, BigDecimal> deltas = new TreeMap<>();
                for (Map<String, String> lot : custLots) {
                    String dateStr = getField(lot, iac.getLotAllocationDateField());
                    if (dateStr == null || dateStr.isEmpty()) continue;
                    LocalDate lotDate = LocalDate.parse(dateStr);
                    BigDecimal qty = parseBigDecimal(getField(lot, iac.getLotQuantityField()));
                    String direction = getField(lot, iac.getLotDirectionField());
                    if ("SELL".equalsIgnoreCase(direction)) {
                        qty = qty.negate();
                    }
                    deltas.merge(lotDate, qty, BigDecimal::add);
                }

                if (deltas.isEmpty()) continue;

                // Compute position_before: sum of all deltas before periodStart
                BigDecimal position = BigDecimal.ZERO;
                for (Map.Entry<LocalDate, BigDecimal> d : deltas.entrySet()) {
                    if (d.getKey().isBefore(pStart)) {
                        position = position.add(d.getValue());
                    }
                }

                // Walk events within [periodStart, periodEnd], computing share-days
                BigDecimal shareDays = BigDecimal.ZERO;
                long holdingDays = 0;
                LocalDate cursor = pStart;

                // Get events within period
                TreeMap<LocalDate, BigDecimal> periodDeltas = new TreeMap<>(
                        deltas.subMap(pStart, true, pEnd, true));

                for (Map.Entry<LocalDate, BigDecimal> event : periodDeltas.entrySet()) {
                    LocalDate eventDate = event.getKey();
                    long days = ChronoUnit.DAYS.between(cursor, eventDate);
                    if (days > 0 && position.compareTo(BigDecimal.ZERO) > 0) {
                        shareDays = shareDays.add(position.multiply(BigDecimal.valueOf(days)));
                        holdingDays += days;
                    }
                    position = position.add(event.getValue());
                    cursor = eventDate;
                }

                // Carry to periodEnd
                long remainingDays = ChronoUnit.DAYS.between(cursor, pEnd);
                if (remainingDays > 0 && position.compareTo(BigDecimal.ZERO) > 0) {
                    shareDays = shareDays.add(position.multiply(BigDecimal.valueOf(remainingDays)));
                    holdingDays += remainingDays;
                }

                if (shareDays.compareTo(BigDecimal.ZERO) > 0) {
                    customerShareDays.put(customerId, shareDays);
                    customerHoldingDays.put(customerId, holdingDays);
                    customerAvgQty.put(customerId,
                            holdingDays > 0
                                    ? shareDays.divide(BigDecimal.valueOf(holdingDays), 6, RoundingMode.HALF_UP)
                                    : BigDecimal.ZERO);
                }
            }

            if (customerShareDays.isEmpty()) {
                throw new IllegalArgumentException(
                        "No holdings found for asset " + assetId + " in period " + periodStart + " to " + periodEnd);
            }

            // === PHASE 3: COMPUTE ALLOCATIONS ===

            BigDecimal totalShareDays = BigDecimal.ZERO;
            for (BigDecimal sd : customerShareDays.values()) {
                totalShareDays = totalShareDays.add(sd);
            }

            Map<String, BigDecimal> allocPcts = new LinkedHashMap<>();
            Map<String, BigDecimal> allocAmounts = new LinkedHashMap<>();
            Map<String, BigDecimal> allocAmountsEur = new LinkedHashMap<>();

            for (Map.Entry<String, BigDecimal> entry : customerShareDays.entrySet()) {
                BigDecimal pct = entry.getValue().divide(totalShareDays, 6, RoundingMode.HALF_UP);
                BigDecimal amt = totalAmount.multiply(pct).setScale(6, RoundingMode.HALF_UP);
                BigDecimal amtEur = amt.multiply(fxRate).setScale(6, RoundingMode.HALF_UP);
                allocPcts.put(entry.getKey(), pct);
                allocAmounts.put(entry.getKey(), amt);
                allocAmountsEur.put(entry.getKey(), amtEur);
            }

            // Rounding remainder adjustment
            BigDecimal sumAllocated = BigDecimal.ZERO;
            for (BigDecimal amt : allocAmounts.values()) {
                sumAllocated = sumAllocated.add(amt);
            }
            BigDecimal remainder = totalAmount.subtract(sumAllocated);
            if (remainder.compareTo(BigDecimal.ZERO) != 0) {
                // Add remainder to the customer with the largest allocation
                String largestCustomer = null;
                BigDecimal largestAmt = BigDecimal.ZERO;
                for (Map.Entry<String, BigDecimal> e : allocAmounts.entrySet()) {
                    if (e.getValue().abs().compareTo(largestAmt.abs()) > 0) {
                        largestAmt = e.getValue();
                        largestCustomer = e.getKey();
                    }
                }
                if (largestCustomer != null) {
                    allocAmounts.put(largestCustomer, largestAmt.add(remainder));
                    allocAmountsEur.put(largestCustomer,
                            allocAmounts.get(largestCustomer).multiply(fxRate).setScale(6, RoundingMode.HALF_UP));
                }
            }

            // === PHASE 4: WRITE (skipped if preview) ===

            String username = getCurrentUser();
            String allocationDate = enrichment.get(iac.getEnrichmentTrxDateField());
            List<Map<String, Object>> allocationsList = new ArrayList<>();

            for (Map.Entry<String, BigDecimal> entry : customerShareDays.entrySet()) {
                String customerId = entry.getKey();
                BigDecimal shareDays = entry.getValue();

                // Resolve customer display name
                String displayName = "";
                Map<String, String> cust = JdbcHelper.loadRowByField(
                        conn, iac.getCustomerTable(), "customerId", customerId);
                if (cust != null) {
                    String dn = getField(cust, iac.getCustomerDisplayNameField());
                    if (dn != null) displayName = dn;
                }

                String generatedId = preview ? "" : generateId(
                        iac.getIncomeAllocIdFormat(), iac.getIncomeAllocIdEnvVar());

                BigDecimal allocPct = allocPcts.get(customerId);
                BigDecimal allocAmt = allocAmounts.get(customerId);
                BigDecimal allocAmtEur = allocAmountsEur.get(customerId);

                if (!preview) {
                    Map<String, String> iaFields = new LinkedHashMap<>();
                    iaFields.put("incomeAllocId", generatedId);
                    iaFields.put("sourceEnrichmentId", enrichmentId);
                    iaFields.put("customerId", customerId);
                    iaFields.put("customerDisplayName", displayName);
                    iaFields.put("assetId", assetId);
                    iaFields.put("assetTicker", assetTicker);
                    iaFields.put("currency", currency != null ? currency : "");
                    iaFields.put("accrualPeriodStart", periodStart);
                    iaFields.put("accrualPeriodEnd", periodEnd);
                    iaFields.put("holdingDays", String.valueOf(customerHoldingDays.get(customerId)));
                    iaFields.put("averageQuantityHeld", customerAvgQty.get(customerId).toPlainString());
                    iaFields.put("shareDays", shareDays.toPlainString());
                    iaFields.put("totalShareDays", totalShareDays.toPlainString());
                    iaFields.put("allocationPercentage", allocPct.toPlainString());
                    iaFields.put("allocatedAmount", allocAmt.toPlainString());
                    iaFields.put("allocatedAmountEur", allocAmtEur.toPlainString());
                    iaFields.put("allocationDate", allocationDate != null ? allocationDate : "");
                    iaFields.put("status", "allocated");

                    JdbcHelper.insertRow(conn, iac.getIncomeAllocTable(), generatedId, iaFields, username);
                }

                Map<String, Object> a = new LinkedHashMap<>();
                a.put("incomeAllocId", generatedId);
                a.put("customerId", customerId);
                a.put("customerName", displayName);
                a.put("shareDays", shareDays.doubleValue());
                a.put("allocationPct", allocPct.doubleValue());
                a.put("allocatedAmount", allocAmt.doubleValue());
                a.put("allocatedAmountEur", allocAmtEur.doubleValue());
                allocationsList.add(a);
            }

            if (!preview) {
                // Update enrichment record
                String existingNotes = enrichment.get(iac.getEnrichmentNotesField());
                String noteAppend = "Income allocated to " + customerShareDays.size()
                        + " customers (share-days: " + totalShareDays.toPlainString() + ")";
                String newNotes = (existingNotes != null && !existingNotes.isEmpty())
                        ? existingNotes + "\n" + noteAppend : noteAppend;

                Map<String, String> enrichUpdate = new LinkedHashMap<>();
                enrichUpdate.put(iac.getEnrichmentAllocStatusField(), "allocated");
                enrichUpdate.put(iac.getEnrichmentNotesField(), newNotes);
                JdbcHelper.updateColumns(conn, enrichmentTable, enrichmentId, enrichUpdate, username);
            }

            conn.commit();
            committed = true;

            // === PHASE 5: RETURN ===

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("enrichmentId", enrichmentId);
            result.put("asset", assetTicker);
            result.put("totalAmount", totalAmount.doubleValue());
            result.put("currency", currency);
            result.put("accrualPeriodStart", periodStart);
            result.put("accrualPeriodEnd", periodEnd);
            result.put("totalShareDays", totalShareDays.doubleValue());
            result.put("allocations", allocationsList);
            result.put("allocationStatus", preview ? "preview" : "allocated");
            return result;

        } catch (RecordNotFoundException | IllegalArgumentException | IllegalStateException e) {
            if (conn != null && !committed) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
            throw e;
        } catch (Exception e) {
            if (conn != null && !committed) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
            throw new SQLException("Income allocation failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
            JdbcHelper.closeQuietly(conn);
        }
    }

    /**
     * Generate a sequential ID using Joget's EnvironmentVariableDao.
     */
    String generateId(String format, String envVariable) {
        try {
            org.joget.apps.app.dao.EnvironmentVariableDao envDao =
                (org.joget.apps.app.dao.EnvironmentVariableDao)
                    AppUtil.getApplicationContext().getBean("environmentVariableDao");
            org.joget.apps.app.model.AppDefinition appDef = AppUtil.getCurrentAppDefinition();
            Integer counter = envDao.getIncreasedCounter(envVariable, "value", appDef);
            int qCount = format.length() - format.replace("?", "").length();
            String padded = String.format("%0" + qCount + "d", counter);
            StringBuilder sb = new StringBuilder();
            int pIdx = 0;
            for (int i = 0; i < format.length(); i++) {
                if (format.charAt(i) == '?' && pIdx < padded.length()) {
                    sb.append(padded.charAt(pIdx++));
                } else {
                    sb.append(format.charAt(i));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("ID generation failed for format '" + format
                + "' (env var: " + envVariable + "): " + e.getMessage(), e);
        }
    }

    private int countActivePositions(Connection conn, String positionTable,
                                      String customerId) throws SQLException {
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

    private BigDecimal sumPositionCosts(Connection conn, String positionTable,
                                         String customerId) throws SQLException {
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

    private BigDecimal sumPositionCostsEur(Connection conn, String positionTable,
                                            String customerId) throws SQLException {
        String sql = "SELECT COALESCE(SUM(CAST("
                + JdbcHelper.dbCol("totalCostBasisEur") + " AS DECIMAL(20,6))), 0)"
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

    /**
     * Case-insensitive map get — H2 lowercases column names but production MySQL preserves case.
     */
    private static String getField(Map<String, String> row, String fieldName) {
        String v = row.get(fieldName);
        if (v != null) return v;
        return row.get(fieldName.toLowerCase());
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

    void setDao(FormDataDao dao) {
        this.daoOverride = dao;
    }

    private FormDataDao getDao() {
        if (daoOverride != null) return daoOverride;
        return (FormDataDao) AppUtil.getApplicationContext().getBean("formDataDao");
    }

    /**
     * Gets the current logged-in user from Joget's WorkflowUserManager.
     * Falls back to "enrichment-api" if unavailable (e.g., in tests or background jobs).
     */
    private String getCurrentUser() {
        try {
            WorkflowUserManager wum = (WorkflowUserManager)
                    AppUtil.getApplicationContext().getBean("workflowUserManager");
            String username = wum.getCurrentUsername();
            if (username != null && !username.isEmpty()) return username;
        } catch (Exception ignored) {}
        return "enrichment-api";
    }

    /**
     * Validates a transition via StatusManager.canTransition() then writes
     * the status change and audit entry via JDBC — all on the same connection.
     */
    private void transitionJdbc(Connection conn, String tableName, String recordId,
                                Status fromStatus, Status toStatus,
                                String triggeredBy, String reason)
            throws SQLException, InvalidTransitionException {
        if (!StatusManager.canTransition(EntityType.ENRICHMENT, fromStatus, toStatus)) {
            throw new InvalidTransitionException(EntityType.ENRICHMENT, recordId,
                    fromStatus, toStatus);
        }

        Map<String, String> updates = new HashMap<>();
        updates.put("status", toStatus.getCode());
        JdbcHelper.updateColumns(conn, tableName, recordId, updates, triggeredBy);

        JdbcHelper.insertAudit(conn, EntityType.ENRICHMENT, recordId,
                fromStatus != null ? fromStatus.getCode() : "null",
                toStatus.getCode(), triggeredBy, reason);
    }
}
