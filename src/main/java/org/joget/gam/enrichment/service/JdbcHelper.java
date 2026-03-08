package org.joget.gam.enrichment.service;

import com.fiscaladmin.gam.framework.status.EntityType;
import org.joget.apps.app.service.AppUtil;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JDBC utility for direct database access when FormDataDao is insufficient
 * (GROUP BY queries, multi-statement transactions).
 * <p>
 * DB naming: table = app_fd_{tableName}, custom columns = c_{fieldId},
 * standard columns = id, dateCreated, dateModified, createdBy, modifiedBy.
 */
public class JdbcHelper {

    private static final String TABLE_PREFIX = "app_fd_";
    private static final String COL_PREFIX = "c_";

    /** Returns the actual DB table name (with app_fd_ prefix). */
    public static String dbTable(String tableName) {
        return TABLE_PREFIX + tableName;
    }

    /** Returns the actual DB column name for a custom field (with c_ prefix). */
    public static String dbCol(String fieldId) {
        return COL_PREFIX + fieldId;
    }

    /** Gets a JDBC Connection from the Joget DataSource. */
    public static Connection getConnection() throws SQLException {
        DataSource ds = (DataSource) AppUtil.getApplicationContext().getBean("setupDataSource");
        return ds.getConnection();
    }

    /**
     * Loads a single row by ID via JDBC.
     * Returns Map of c_columnName to value (raw DB column names), or null if not found.
     */
    public static Map<String, String> loadRow(Connection conn, String tableName,
                                               String id) throws SQLException {
        String sql = "SELECT * FROM " + dbTable(tableName) + " WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Map<String, String> row = new HashMap<>();
                ResultSetMetaData meta = rs.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String colName = meta.getColumnName(i);
                    String value = rs.getString(i);
                    row.put(colName, value);
                }
                return row;
            }
        }
    }

    /**
     * Loads a row and returns field values using form element IDs (without c_ prefix).
     * Standard columns (id, dateCreated, etc.) are included as-is.
     */
    public static Map<String, String> loadRowByFieldId(Connection conn, String tableName,
                                                        String id) throws SQLException {
        Map<String, String> raw = loadRow(conn, tableName, id);
        if (raw == null) return null;

        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String col = entry.getKey();
            if (col.startsWith(COL_PREFIX)) {
                result.put(col.substring(COL_PREFIX.length()), entry.getValue());
            } else {
                result.put(col, entry.getValue());
            }
        }
        return result;
    }

    /** Updates specific custom field columns on a row. Keys are form element IDs (no c_ prefix). */
    public static void updateColumns(Connection conn, String tableName, String id,
                                      Map<String, String> fields, String username) throws SQLException {
        if (fields.isEmpty()) return;

        StringBuilder sql = new StringBuilder("UPDATE ");
        sql.append(dbTable(tableName)).append(" SET ");

        int i = 0;
        for (String fieldId : fields.keySet()) {
            if (i > 0) sql.append(", ");
            sql.append(dbCol(fieldId)).append(" = ?");
            i++;
        }
        sql.append(", dateModified = NOW(), modifiedBy = ? WHERE id = ?");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (String fieldId : fields.keySet()) {
                ps.setString(idx++, fields.get(fieldId));
            }
            ps.setString(idx++, username);
            ps.setString(idx, id);
            ps.executeUpdate();
        }
    }

    /** Inserts an audit row into app_fd_audit_log. */
    public static void insertAudit(Connection conn, EntityType entityType, String entityId,
                                    String fromStatus, String toStatus,
                                    String triggeredBy, String reason) throws SQLException {
        String sql = "INSERT INTO " + dbTable("audit_log")
                + " (id, dateCreated, dateModified, "
                + dbCol("entity_type") + ", "
                + dbCol("entity_id") + ", "
                + dbCol("from_status") + ", "
                + dbCol("to_status") + ", "
                + dbCol("triggered_by") + ", "
                + dbCol("reason") + ", "
                + dbCol("timestamp")
                + ") VALUES (?, NOW(), NOW(), ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, entityType.name());
            ps.setString(3, entityId);
            ps.setString(4, fromStatus);
            ps.setString(5, toStatus);
            ps.setString(6, triggeredBy);
            ps.setString(7, reason);
            ps.setString(8, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    /**
     * Inserts a new row into a form table. The columns map uses form element IDs (no c_ prefix).
     * Standard columns (id, dateCreated, dateModified, createdBy, modifiedBy) are set automatically.
     */
    public static void insertRow(Connection conn, String tableName, String id,
                                  Map<String, String> fields, String username) throws SQLException {
        StringBuilder cols = new StringBuilder("id, dateCreated, dateModified, createdBy, modifiedBy");
        StringBuilder vals = new StringBuilder("?, NOW(), NOW(), ?, ?");

        for (String fieldId : fields.keySet()) {
            cols.append(", ").append(dbCol(fieldId));
            vals.append(", ?");
        }

        String sql = "INSERT INTO " + dbTable(tableName) + " (" + cols + ") VALUES (" + vals + ")";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, username);
            ps.setString(3, username);
            int idx = 4;
            for (String fieldId : fields.keySet()) {
                ps.setString(idx++, fields.get(fieldId));
            }
            ps.executeUpdate();
        }
    }

    /** Deletes a row by ID. */
    public static void deleteRow(Connection conn, String tableName,
                                  String id) throws SQLException {
        String sql = "DELETE FROM " + dbTable(tableName) + " WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    /** Closes a connection quietly. */
    public static void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // intentionally ignored
            }
        }
    }
}
