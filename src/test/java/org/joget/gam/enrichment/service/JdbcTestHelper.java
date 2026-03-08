package org.joget.gam.enrichment.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared H2 utilities for JDBC-based tests (confirm, split, merge, JdbcHelper).
 */
public final class JdbcTestHelper {

    public static final String TABLE = "trxEnrichment";

    private JdbcTestHelper() {}

    /**
     * Creates the H2 tables used by confirm/split/merge operations.
     */
    public static void createTables(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS app_fd_trxEnrichment ("
                    + "id VARCHAR(255) PRIMARY KEY, "
                    + "dateCreated TIMESTAMP, "
                    + "dateModified TIMESTAMP, "
                    + "createdBy VARCHAR(255), "
                    + "modifiedBy VARCHAR(255), "
                    + "c_status VARCHAR(50), "
                    + "c_version VARCHAR(10), "
                    + "c_statement_id VARCHAR(255), "
                    + "c_validated_currency VARCHAR(10), "
                    + "c_original_amount VARCHAR(50), "
                    + "c_fee_amount VARCHAR(50), "
                    + "c_total_amount VARCHAR(50), "
                    + "c_base_amount_eur VARCHAR(50), "
                    + "c_transaction_date VARCHAR(50), "
                    + "c_settlement_date VARCHAR(50), "
                    + "c_debit_credit VARCHAR(10), "
                    + "c_internal_type VARCHAR(50), "
                    + "c_customer_code VARCHAR(255), "
                    + "c_resolved_customer_id VARCHAR(255), "
                    + "c_description VARCHAR(1000), "
                    + "c_origin VARCHAR(50), "
                    + "c_parent_enrichment_id VARCHAR(255), "
                    + "c_group_id VARCHAR(255), "
                    + "c_split_sequence VARCHAR(10), "
                    + "c_lineage_note VARCHAR(1000), "
                    + "c_fx_rate_to_eur VARCHAR(50), "
                    + "c_processing_notes VARCHAR(2000), "
                    + "c_confirmed_by VARCHAR(255), "
                    + "c_confirmed_at VARCHAR(100), "
                    // WS-2 workspace operations fields
                    + "c_matched_rule_id VARCHAR(255), "
                    + "c_type_confidence VARCHAR(50), "
                    + "c_customer_display_name VARCHAR(255), "
                    + "c_customer_match_method VARCHAR(50), "
                    + "c_pair_id VARCHAR(255), "
                    + "c_acc_post_id VARCHAR(255), "
                    + "c_base_fee_eur VARCHAR(50), "
                    + "c_loan_id VARCHAR(255), "
                    + "c_loan_direction VARCHAR(50), "
                    + "c_loan_resolution_method VARCHAR(50), "
                    + "c_source_reference VARCHAR(255), "
                    + "c_gl_debit_override VARCHAR(255), "
                    + "c_gl_credit_override VARCHAR(255), "
                    + "c_gl_override_reason VARCHAR(1000), "
                    + "c_fund_allocation_status VARCHAR(50), "
                    + "c_period_locked VARCHAR(10), "
                    + "c_source_tp VARCHAR(50), "
                    + "c_has_fee VARCHAR(10), "
                    // H2 DATABASE_TO_LOWER lowercases standard columns (dateCreated→datecreated).
                    // split/merge copies all parent fields then removes camelCase keys,
                    // leaving lowercase keys that get c_ prefix. Add those columns so
                    // insertRow doesn't fail on c_datecreated etc.
                    + "c_datecreated VARCHAR(100), "
                    + "c_datemodified VARCHAR(100), "
                    + "c_createdby VARCHAR(255), "
                    + "c_modifiedby VARCHAR(255)"
                    + ")");

            st.execute("CREATE TABLE IF NOT EXISTS app_fd_audit_log ("
                    + "id VARCHAR(255) PRIMARY KEY, "
                    + "dateCreated TIMESTAMP, "
                    + "dateModified TIMESTAMP, "
                    + "c_entity_type VARCHAR(50), "
                    + "c_entity_id VARCHAR(255), "
                    + "c_from_status VARCHAR(50), "
                    + "c_to_status VARCHAR(50), "
                    + "c_triggered_by VARCHAR(255), "
                    + "c_reason VARCHAR(1000), "
                    + "c_timestamp VARCHAR(100)"
                    + ")");
        }
    }

    /**
     * Inserts an enrichment row. Keys are field IDs (no c_ prefix).
     */
    public static void insertEnrichmentRow(Connection conn, String id,
                                           Map<String, String> fields) throws SQLException {
        StringBuilder cols = new StringBuilder("id, dateCreated, dateModified");
        StringBuilder vals = new StringBuilder("?, NOW(), NOW()");

        for (String fieldId : fields.keySet()) {
            cols.append(", c_").append(fieldId);
            vals.append(", ?");
        }

        String sql = "INSERT INTO app_fd_trxEnrichment (" + cols + ") VALUES (" + vals + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            int idx = 2;
            for (String fieldId : fields.keySet()) {
                ps.setString(idx++, fields.get(fieldId));
            }
            ps.executeUpdate();
        }
    }

    /**
     * Reads a row from trxEnrichment, returns lowercase column names.
     */
    public static Map<String, String> readRow(Connection conn, String id) throws SQLException {
        String sql = "SELECT * FROM app_fd_trxEnrichment WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, String> row = new HashMap<>();
                ResultSetMetaData meta = rs.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnName(i).toLowerCase(), rs.getString(i));
                }
                return row;
            }
        }
    }

    /**
     * Reads audit entries for a given entity ID, ordered by dateCreated.
     */
    public static List<Map<String, String>> readAuditEntries(Connection conn,
                                                              String entityId) throws SQLException {
        String sql = "SELECT * FROM app_fd_audit_log WHERE c_entity_id = ? ORDER BY dateCreated";
        List<Map<String, String>> entries = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> row = new HashMap<>();
                    ResultSetMetaData meta = rs.getMetaData();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnName(i).toLowerCase(), rs.getString(i));
                    }
                    entries.add(row);
                }
            }
        }
        return entries;
    }

    /**
     * Returns a ValidationConfig JSON string with confirmation section populated.
     */
    public static ValidationConfig confirmConfig() {
        return ValidationConfig.parse("{"
                + "\"baseCurrency\": \"EUR\","
                + "\"requiredFields\": [\"customer_code\", \"internal_type\", \"debit_credit\"],"
                + "\"confirmation\": {"
                + "  \"confirmedByField\": \"confirmed_by\","
                + "  \"confirmedAtField\": \"confirmed_at\""
                + "}"
                + "}");
    }

    /**
     * Returns a ValidationConfig with splitMerge and reconciliation sections populated.
     */
    public static ValidationConfig splitMergeConfig() {
        return ValidationConfig.parse("{"
                + "\"baseCurrency\": \"EUR\","
                + "\"requiredFields\": [],"
                + "\"splitMerge\": {"
                + "  \"amountField\": \"original_amount\","
                + "  \"feeField\": \"fee_amount\","
                + "  \"totalField\": \"total_amount\","
                + "  \"eurAmountField\": \"base_amount_eur\","
                + "  \"fxRateField\": \"fx_rate_to_eur\","
                + "  \"customerField\": \"customer_code\","
                + "  \"originField\": \"origin\","
                + "  \"parentIdField\": \"parent_enrichment_id\","
                + "  \"groupIdField\": \"group_id\","
                + "  \"sequenceField\": \"split_sequence\","
                + "  \"lineageNoteField\": \"lineage_note\","
                + "  \"statusField\": \"status\""
                + "},"
                + "\"reconciliation\": {"
                + "  \"amountField\": \"total_amount\","
                + "  \"currencyField\": \"validated_currency\","
                + "  \"statementField\": \"statement_id\","
                + "  \"originField\": \"origin\","
                + "  \"manualOriginValue\": \"manual\","
                + "  \"statusField\": \"status\""
                + "}"
                + "}");
    }

    /**
     * Builds a mutable field map from key-value pairs.
     */
    public static Map<String, String> fields(String... pairs) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }
}
