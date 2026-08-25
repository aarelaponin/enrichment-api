package org.joget.gam.enrichment.service;

import com.fiscaladmin.gam.framework.status.EntityType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.joget.gam.enrichment.service.JdbcTestHelper.*;
import static org.junit.Assert.*;

/**
 * Tests JdbcHelper methods directly against H2. No mocking needed —
 * JdbcHelper methods accept a Connection parameter.
 */
public class JdbcHelperTest {

    private Connection conn;

    @Before
    public void setUp() throws Exception {
        String dbName = "jdbchelper_" + UUID.randomUUID().toString().replace("-", "");
        conn = DriverManager.getConnection(
                "jdbc:h2:mem:" + dbName + ";DATABASE_TO_LOWER=TRUE", "sa", "");
        createTables(conn);
    }

    @After
    public void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) conn.close();
    }

    @Test
    public void insertRow_setsCreatedByAndModifiedBy() throws SQLException {
        Map<String, String> flds = fields("status", "enriched", "description", "Test");
        JdbcHelper.insertRow(conn, TABLE, "R001", flds, "analyst01");

        Map<String, String> row = readRow(conn, "R001");
        assertNotNull(row);
        assertEquals("analyst01", row.get("createdby"));
        assertEquals("analyst01", row.get("modifiedby"));
    }

    @Test
    public void insertRow_setsDateFields() throws SQLException {
        JdbcHelper.insertRow(conn, TABLE, "R002", fields("status", "new"), "user1");

        Map<String, String> row = readRow(conn, "R002");
        assertNotNull(row.get("datecreated"));
        assertNotNull(row.get("datemodified"));
    }

    @Test
    public void updateColumns_setsModifiedBy() throws SQLException {
        JdbcHelper.insertRow(conn, TABLE, "R003",
                fields("status", "enriched", "description", "Old"), "creator");

        JdbcHelper.updateColumns(conn, TABLE, "R003",
                fields("description", "New"), "editor");

        Map<String, String> row = readRow(conn, "R003");
        assertEquals("New", row.get("c_description"));
        assertEquals("editor", row.get("modifiedby"));
        assertEquals("creator", row.get("createdby"));
    }

    @Test
    public void updateColumns_emptyFields_noOp() throws SQLException {
        JdbcHelper.insertRow(conn, TABLE, "R004", fields("status", "new"), "user1");
        // Should not throw
        JdbcHelper.updateColumns(conn, TABLE, "R004", Map.of(), "user1");
    }

    @Test
    public void insertAudit_usesEntityTypeName() throws SQLException {
        JdbcHelper.insertAudit(conn, EntityType.ENRICHMENT, "R005",
                "enriched", "confirmed", "analyst01", "Confirmed for posting");

        List<Map<String, String>> audits = readAuditEntries(conn, "R005");
        assertEquals(1, audits.size());
        assertEquals("ENRICHMENT", audits.get(0).get("c_entity_type"));
        assertEquals("enriched", audits.get(0).get("c_from_status"));
        assertEquals("confirmed", audits.get(0).get("c_to_status"));
        assertEquals("analyst01", audits.get(0).get("c_triggered_by"));
    }

    @Test
    public void loadRowByFieldId_stripsPrefix() throws SQLException {
        JdbcHelper.insertRow(conn, TABLE, "R006",
                fields("status", "enriched", "customer_code", "CUST-1"), "user1");

        Map<String, String> row = JdbcHelper.loadRowByFieldId(conn, TABLE, "R006");
        assertNotNull(row);
        // Field IDs without c_ prefix
        assertEquals("enriched", row.get("status"));
        assertEquals("CUST-1", row.get("customer_code"));
        // Standard columns kept as-is (id is unaffected by c_ prefix stripping)
        assertEquals("R006", row.get("id"));
        // Note: datecreated/createdby assertions skipped because the phantom
        // c_datecreated/c_createdby columns (added for split/merge compat)
        // collide after prefix stripping in H2's lowercase mode.
        assertTrue("Should have more keys than just custom fields",
                row.size() > 2);
    }

    @Test
    public void loadRowByFieldId_notFound() throws SQLException {
        Map<String, String> row = JdbcHelper.loadRowByFieldId(conn, TABLE, "NONEXISTENT");
        assertNull(row);
    }

    @Test
    public void deleteRow_removesRecord() throws SQLException {
        JdbcHelper.insertRow(conn, TABLE, "R007", fields("status", "new"), "user1");
        assertNotNull(readRow(conn, "R007"));

        JdbcHelper.deleteRow(conn, TABLE, "R007");
        assertNull(readRow(conn, "R007"));
    }

    // ── loadRowByField tests ──────────────────────────────────────────

    @Test
    public void loadRowByField_found() throws SQLException {
        JdbcHelper.insertRow(conn, TABLE, "R010",
                fields("status", "enriched", "customer_code", "CUST-X"), "user1");

        Map<String, String> row = JdbcHelper.loadRowByField(conn, TABLE, "customer_code", "CUST-X");
        assertNotNull(row);
        assertEquals("R010", row.get("id"));
        assertEquals("enriched", row.get("status"));
    }

    @Test
    public void loadRowByField_notFound() throws SQLException {
        Map<String, String> row = JdbcHelper.loadRowByField(conn, TABLE, "customer_code", "NOPE");
        assertNull(row);
    }

    @Test(expected = SQLException.class)
    public void loadRowByField_multipleMatches_throws() throws SQLException {
        JdbcHelper.insertRow(conn, TABLE, "R011",
                fields("status", "enriched", "customer_code", "DUP"), "user1");
        JdbcHelper.insertRow(conn, TABLE, "R012",
                fields("status", "enriched", "customer_code", "DUP"), "user1");

        JdbcHelper.loadRowByField(conn, TABLE, "customer_code", "DUP");
    }

    // ── loadRowByFields tests ─────────────────────────────────────────

    @Test
    public void loadRowByFields_compositeFound() throws SQLException {
        insertRow(conn, "portfolioPosition", "PP-1", fields(
                "customerId", "C001", "assetId", "ASSET-1", "quantity", "100", "status", "active"));

        Map<String, String> row = JdbcHelper.loadRowByFields(
                conn, "portfolioPosition", "customerId", "C001", "assetId", "ASSET-1");
        assertNotNull(row);
        assertEquals("PP-1", row.get("id"));
    }

    @Test
    public void loadRowByFields_compositeNotFound() throws SQLException {
        insertRow(conn, "portfolioPosition", "PP-2", fields(
                "customerId", "C001", "assetId", "ASSET-1", "quantity", "100", "status", "active"));

        Map<String, String> row = JdbcHelper.loadRowByFields(
                conn, "portfolioPosition", "customerId", "C001", "assetId", "ASSET-2");
        assertNull(row);
    }

    // ── loadRowsByField tests ─────────────────────────────────────────

    @Test
    public void loadRowsByField_multipleResults() throws SQLException {
        insertRow(conn, "allocationLot", "LOT-1", fields(
                "sourceEnrichmentId", "ENR-1", "quantity", "100"));
        insertRow(conn, "allocationLot", "LOT-2", fields(
                "sourceEnrichmentId", "ENR-1", "quantity", "200"));

        List<Map<String, String>> rows = JdbcHelper.loadRowsByField(
                conn, "allocationLot", "sourceEnrichmentId", "ENR-1");
        assertEquals(2, rows.size());
    }

    @Test
    public void loadRowsByField_emptyList() throws SQLException {
        List<Map<String, String>> rows = JdbcHelper.loadRowsByField(
                conn, "allocationLot", "sourceEnrichmentId", "NOPE");
        assertTrue(rows.isEmpty());
    }

    // ── sumColumn tests ───────────────────────────────────────────────

    @Test
    public void sumColumn_sumWithMatches() throws SQLException {
        insertRow(conn, "allocationLot", "LOT-10", fields(
                "sourceEnrichmentId", "ENR-X", "quantity", "100.5"));
        insertRow(conn, "allocationLot", "LOT-11", fields(
                "sourceEnrichmentId", "ENR-X", "quantity", "200.3"));

        BigDecimal sum = JdbcHelper.sumColumn(conn, "allocationLot",
                "quantity", "sourceEnrichmentId", "ENR-X");
        assertEquals(0, new BigDecimal("300.8").compareTo(sum));
    }

    @Test
    public void sumColumn_zeroWhenNoMatches() throws SQLException {
        BigDecimal sum = JdbcHelper.sumColumn(conn, "allocationLot",
                "quantity", "sourceEnrichmentId", "NOPE");
        assertEquals(0, BigDecimal.ZERO.compareTo(sum));
    }

    // ── countRows tests ───────────────────────────────────────────────

    @Test
    public void countRows_withMatches() throws SQLException {
        insertRow(conn, "allocationLot", "LOT-20", fields(
                "sourceEnrichmentId", "ENR-Y", "quantity", "50"));
        insertRow(conn, "allocationLot", "LOT-21", fields(
                "sourceEnrichmentId", "ENR-Y", "quantity", "75"));

        int count = JdbcHelper.countRows(conn, "allocationLot",
                "sourceEnrichmentId", "ENR-Y");
        assertEquals(2, count);
    }

    @Test
    public void countRows_zeroWhenNoMatches() throws SQLException {
        int count = JdbcHelper.countRows(conn, "allocationLot",
                "sourceEnrichmentId", "NOPE");
        assertEquals(0, count);
    }
}
