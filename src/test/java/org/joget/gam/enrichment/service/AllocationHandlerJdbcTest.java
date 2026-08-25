package org.joget.gam.enrichment.service;

import org.joget.apps.app.service.AppUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.joget.gam.enrichment.service.JdbcTestHelper.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the JDBC query logic used by allocation handler endpoints
 * (getCustomers, getSecuTransaction, getAllocationSummary).
 * Uses the same H2 + MockedStatic pattern as EnrichmentServiceAllocateTest.
 */
public class AllocationHandlerJdbcTest {

    private Connection keepAliveConn;
    private DataSource mockDs;
    private MockedStatic<AppUtil> mockedAppUtil;
    private ValidationConfig.AllocationConfig ac;
    private String dbUrl;

    @Before
    public void setUp() throws Exception {
        String dbName = "handler_" + UUID.randomUUID().toString().replace("-", "");
        dbUrl = "jdbc:h2:mem:" + dbName + ";DATABASE_TO_LOWER=TRUE";
        keepAliveConn = DriverManager.getConnection(dbUrl, "sa", "");
        createTables(keepAliveConn);

        mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenAnswer(inv ->
                DriverManager.getConnection(dbUrl, "sa", ""));

        ApplicationContext mockCtx = mock(ApplicationContext.class);
        when(mockCtx.getBean("setupDataSource")).thenReturn(mockDs);

        mockedAppUtil = mockStatic(AppUtil.class);
        mockedAppUtil.when(AppUtil::getApplicationContext).thenReturn(mockCtx);

        ac = ValidationConfig.AllocationConfig.defaults();
    }

    @After
    public void tearDown() throws Exception {
        mockedAppUtil.close();
        if (keepAliveConn != null && !keepAliveConn.isClosed()) keepAliveConn.close();
    }

    // ── getCustomers query logic ────────────────────────────────────────

    @Test
    public void getCustomers_noSearch_returnsNonFunds() throws Exception {
        insertRow(keepAliveConn, "customer", "C001", fields(
                "customerId", "C001",
                "displayName", "Alice Doe", "is_fund", "no"));
        insertRow(keepAliveConn, "customer", "C002", fields(
                "customerId", "C002",
                "displayName", "Bob Smith", "is_fund", "no"));
        insertRow(keepAliveConn, "customer", "FUND-001", fields(
                "customerId", "FUND-001",
                "displayName", "Acme Fund", "is_fund", "yes"));

        Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
        try {
            List<Map<String, Object>> customers = queryCustomers(conn, "");
            assertEquals(2, customers.size());
            // Fund should be excluded
            for (Map<String, Object> c : customers) {
                assertNotEquals("FUND-001", c.get("customerId"));
            }
        } finally {
            conn.close();
        }
    }

    @Test
    public void getCustomers_withSearch_filters() throws Exception {
        insertRow(keepAliveConn, "customer", "C001", fields(
                "customerId", "C001",
                "displayName", "Alice Doe", "is_fund", "no"));
        insertRow(keepAliveConn, "customer", "C002", fields(
                "customerId", "C002",
                "displayName", "Bob Smith", "is_fund", "no"));
        insertRow(keepAliveConn, "customer", "C003", fields(
                "customerId", "C003",
                "displayName", "Alice Wonder", "is_fund", "no"));

        Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
        try {
            List<Map<String, Object>> customers = queryCustomers(conn, "Alice");
            assertEquals(2, customers.size());
            for (Map<String, Object> c : customers) {
                String name = (String) c.get("displayName");
                assertTrue(name.contains("Alice"));
            }
        } finally {
            conn.close();
        }
    }

    @Test
    public void getCustomers_emptyTable_returnsEmpty() throws Exception {
        Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
        try {
            List<Map<String, Object>> customers = queryCustomers(conn, "");
            assertTrue(customers.isEmpty());
        } finally {
            conn.close();
        }
    }

    // ── getSecuTransaction query logic ──────────────────────────────────

    @Test
    public void getSecuTransaction_happyPath() throws Exception {
        insertEnrichmentRow(keepAliveConn, "ENR-H01", fields(
                "status", "enriched",
                "internal_type", "EQ_BUY",
                "source_trx_id", "SECU-H01",
                "resolved_asset_id", "ASSET-AAPL",
                "asset_isin", "US0378331005",
                "transaction_date", "2026-03-10"));

        insertRow(keepAliveConn, "secu_total_trx", "SECU-H01", fields(
                "quantity", "1000", "price", "150.00", "fee", "10.00",
                "amount", "150000", "ticker", "AAPL", "currency", "USD"));

        Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
        try {
            Map<String, String> enrichment = JdbcHelper.loadRowByFieldId(
                    conn, TABLE, "ENR-H01");
            assertNotNull(enrichment);

            String sourceId = enrichment.get(ac.getEnrichmentSourceField());
            assertEquals("SECU-H01", sourceId);

            Map<String, String> secu = JdbcHelper.loadRowByFieldId(
                    conn, ac.getSecuTable(), sourceId);
            assertNotNull(secu);
            assertEquals("1000", secu.get(ac.getSecuQuantityField()));
            assertEquals("150.00", secu.get(ac.getSecuPriceField()));
            assertEquals("10.00", secu.get(ac.getSecuFeeField()));
            assertEquals("AAPL", secu.get(ac.getSecuTickerField()));
            assertEquals("USD", secu.get(ac.getSecuCurrencyField()));
        } finally {
            conn.close();
        }
    }

    @Test
    public void getSecuTransaction_missingSource_errors() throws Exception {
        insertEnrichmentRow(keepAliveConn, "ENR-H02", fields(
                "status", "enriched",
                "internal_type", "EQ_BUY",
                "source_trx_id", ""));

        Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
        try {
            Map<String, String> enrichment = JdbcHelper.loadRowByFieldId(
                    conn, TABLE, "ENR-H02");
            assertNotNull(enrichment);

            String sourceId = enrichment.get(ac.getEnrichmentSourceField());
            assertTrue("source_trx_id should be empty",
                    sourceId == null || sourceId.isEmpty());
        } finally {
            conn.close();
        }
    }

    @Test
    public void getSecuTransaction_secuNotFound_errors() throws Exception {
        insertEnrichmentRow(keepAliveConn, "ENR-H03", fields(
                "status", "enriched",
                "internal_type", "EQ_BUY",
                "source_trx_id", "SECU-DANGLING"));

        Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
        try {
            Map<String, String> enrichment = JdbcHelper.loadRowByFieldId(
                    conn, TABLE, "ENR-H03");
            String sourceId = enrichment.get(ac.getEnrichmentSourceField());
            assertEquals("SECU-DANGLING", sourceId);

            Map<String, String> secu = JdbcHelper.loadRowByFieldId(
                    conn, ac.getSecuTable(), sourceId);
            assertNull("Dangling source_trx_id should yield null secu", secu);
        } finally {
            conn.close();
        }
    }

    // ── getAllocationSummary query logic ─────────────────────────────────

    @Test
    public void getAllocationSummary_noLots_returnsEmpty() throws Exception {
        Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
        try {
            List<Map<String, String>> lots = JdbcHelper.loadRowsByField(
                    conn, ac.getLotTable(), "sourceEnrichmentId", "ENR-NOLOTS");
            assertTrue(lots.isEmpty());

            BigDecimal totalQty = BigDecimal.ZERO;
            for (Map<String, String> lot : lots) {
                String qtyStr = lot.get("quantity");
                if (qtyStr != null && !qtyStr.isEmpty()) {
                    totalQty = totalQty.add(new BigDecimal(qtyStr));
                }
            }
            assertEquals(0, totalQty.compareTo(BigDecimal.ZERO));
        } finally {
            conn.close();
        }
    }

    @Test
    public void getAllocationSummary_multipleLots_aggregates() throws Exception {
        insertRow(keepAliveConn, "allocationLot", "LOT-001", fields(
                "sourceEnrichmentId", "ENR-SUM-01",
                "customerId", "C001",
                "direction", "BUY",
                "quantity", "400",
                "pricePerUnit", "100.00",
                "totalAmount", "40000"));

        insertRow(keepAliveConn, "allocationLot", "LOT-002", fields(
                "sourceEnrichmentId", "ENR-SUM-01",
                "customerId", "C002",
                "direction", "BUY",
                "quantity", "300",
                "pricePerUnit", "100.00",
                "totalAmount", "30000"));

        Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
        try {
            List<Map<String, String>> lots = JdbcHelper.loadRowsByField(
                    conn, ac.getLotTable(), "sourceEnrichmentId", "ENR-SUM-01");
            assertEquals(2, lots.size());

            BigDecimal totalQty = BigDecimal.ZERO;
            for (Map<String, String> lot : lots) {
                String qtyStr = lot.get("quantity");
                if (qtyStr != null && !qtyStr.isEmpty()) {
                    totalQty = totalQty.add(new BigDecimal(qtyStr));
                }
            }
            assertEquals(0, totalQty.compareTo(new BigDecimal("700")));

            // Verify per-lot fields
            // Note: H2 DATABASE_TO_LOWER lowercases camelCase keys → "customerid"
            Map<String, String> lot1 = lots.get(0);
            Map<String, String> lot2 = lots.get(1);
            Set<String> lotCustomerIds = new HashSet<>();
            lotCustomerIds.add(lot1.get("customerid"));
            lotCustomerIds.add(lot2.get("customerid"));
            assertTrue(lotCustomerIds.contains("C001"));
            assertTrue(lotCustomerIds.contains("C002"));
        } finally {
            conn.close();
        }
    }

    @Test
    public void getAllocationSummary_resolvesCustomerNames() throws Exception {
        insertRow(keepAliveConn, "customer", "C001", fields(
                "customerId", "C001",
                "displayName", "Alice Doe", "is_fund", "no"));
        insertRow(keepAliveConn, "customer", "C002", fields(
                "customerId", "C002",
                "displayName", "Bob Smith", "is_fund", "no"));

        insertRow(keepAliveConn, "allocationLot", "LOT-R01", fields(
                "sourceEnrichmentId", "ENR-RES-01",
                "customerId", "C001",
                "direction", "BUY",
                "quantity", "100"));

        insertRow(keepAliveConn, "allocationLot", "LOT-R02", fields(
                "sourceEnrichmentId", "ENR-RES-01",
                "customerId", "C002",
                "direction", "BUY",
                "quantity", "200"));

        Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
        try {
            List<Map<String, String>> lots = JdbcHelper.loadRowsByField(
                    conn, ac.getLotTable(), "sourceEnrichmentId", "ENR-RES-01");

            // Batch-load customer display names (same logic as handler)
            // Note: H2 DATABASE_TO_LOWER lowercases camelCase → "customerid", "displayname"
            Set<String> customerIds = new HashSet<>();
            for (Map<String, String> lot : lots) {
                String cid = lot.get("customerid");
                if (cid != null && !cid.isEmpty()) customerIds.add(cid);
            }
            assertEquals(2, customerIds.size());

            // Verify customer name resolution via direct JDBC lookup
            Map<String, String> customerNames = new HashMap<>();
            for (String cid : customerIds) {
                Map<String, String> cust = JdbcHelper.loadRowByField(
                        conn, ac.getCustomerTable(), "customerId", cid);
                if (cust != null) {
                    // In H2, "displayName" → "displayname" due to DATABASE_TO_LOWER
                    String name = cust.get("displayname");
                    customerNames.put(cid, name);
                }
            }

            assertEquals(2, customerNames.size());
            assertEquals("Alice Doe", customerNames.get("C001"));
            assertEquals("Bob Smith", customerNames.get("C002"));
        } finally {
            conn.close();
        }
    }

    // ── Helper: replicates the customer SQL from handleGetCustomers ──────

    private List<Map<String, Object>> queryCustomers(Connection conn, String search)
            throws Exception {
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

        List<Map<String, Object>> customers = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (!search.isEmpty()) {
                ps.setString(1, "%" + search + "%");
                ps.setString(2, "%" + search + "%");
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("customerId", rs.getString(JdbcHelper.dbCol("customerId")));
                    c.put("displayName", rs.getString(
                            JdbcHelper.dbCol(ac.getCustomerDisplayNameField())));
                    customers.add(c);
                }
            }
        }
        return customers;
    }

    // ── Fix 5: getAllocationSummary financial data tests ─────────────────

    @Test
    public void getAllocationSummary_includesFinancialData() throws Exception {
        insertRow(keepAliveConn, "allocationLot", "LOT-FIN-01", fields(
                "sourceEnrichmentId", "ENR-FIN-01",
                "customerId", "C001",
                "direction", "BUY",
                "quantity", "400",
                "pricePerUnit", "100.00",
                "totalAmount", "40000",
                "feeAmount", "4.00",
                "totalCostWithFees", "40004",
                "currency", "USD",
                "allocationDate", "2026-03-10",
                "totalAmountEur", "36672.00",
                "feeAmountEur", "3.67"));

        insertRow(keepAliveConn, "allocationLot", "LOT-FIN-02", fields(
                "sourceEnrichmentId", "ENR-FIN-01",
                "customerId", "C002",
                "direction", "BUY",
                "quantity", "300",
                "pricePerUnit", "100.00",
                "totalAmount", "30000",
                "feeAmount", "3.00",
                "totalCostWithFees", "30003",
                "currency", "USD",
                "allocationDate", "2026-03-10",
                "totalAmountEur", "27504.00",
                "feeAmountEur", "2.75"));

        Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
        try {
            List<Map<String, String>> lots = JdbcHelper.loadRowsByField(
                    conn, ac.getLotTable(), "sourceEnrichmentId", "ENR-FIN-01");
            assertEquals(2, lots.size());

            for (Map<String, String> lot : lots) {
                assertNotNull("totalAmount should be present", lot.get("totalamount"));
                assertNotNull("feeAmount should be present", lot.get("feeamount"));
                assertNotNull("currency should be present", lot.get("currency"));
                assertNotNull("allocationDate should be present", lot.get("allocationdate"));
                assertNotNull("pricePerUnit should be present", lot.get("priceperunit"));
            }
        } finally {
            conn.close();
        }
    }

    @Test
    public void getAllocationSummary_includesTotals() throws Exception {
        insertRow(keepAliveConn, "allocationLot", "LOT-TOT-01", fields(
                "sourceEnrichmentId", "ENR-TOT-01",
                "customerId", "C001",
                "direction", "BUY",
                "quantity", "400",
                "totalAmount", "40000",
                "feeAmount", "4.00",
                "currency", "USD"));

        insertRow(keepAliveConn, "allocationLot", "LOT-TOT-02", fields(
                "sourceEnrichmentId", "ENR-TOT-01",
                "customerId", "C002",
                "direction", "BUY",
                "quantity", "300",
                "totalAmount", "30000",
                "feeAmount", "3.00",
                "currency", "USD"));

        Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
        try {
            List<Map<String, String>> lots = JdbcHelper.loadRowsByField(
                    conn, ac.getLotTable(), "sourceEnrichmentId", "ENR-TOT-01");

            BigDecimal totalAmount = BigDecimal.ZERO;
            BigDecimal totalFee = BigDecimal.ZERO;
            for (Map<String, String> lot : lots) {
                String amt = lot.get("totalamount");
                String fee = lot.get("feeamount");
                if (amt != null && !amt.isEmpty()) totalAmount = totalAmount.add(new BigDecimal(amt));
                if (fee != null && !fee.isEmpty()) totalFee = totalFee.add(new BigDecimal(fee));
            }

            assertEquals(0, totalAmount.compareTo(new BigDecimal("70000")));
            assertEquals(0, totalFee.compareTo(new BigDecimal("7.00")));
        } finally {
            conn.close();
        }
    }
}
