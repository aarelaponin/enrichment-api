package org.joget.gam.enrichment.service;

import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.workflow.model.service.WorkflowUserManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;

import org.joget.apps.app.dao.EnvironmentVariableDao;
import org.joget.apps.app.model.AppDefinition;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.joget.gam.enrichment.service.JdbcTestHelper.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for EnrichmentService.allocateIncome() — uses MockedStatic + H2.
 */
public class EnrichmentServiceIncomeAllocTest {

    private static final AtomicLong envVarCounter = new AtomicLong(0);

    private Connection keepAliveConn;
    private DataSource mockDs;
    private MockedStatic<AppUtil> mockedAppUtil;
    private EnrichmentService service;
    private ValidationConfig config;
    private String dbUrl;

    @Before
    public void setUp() throws Exception {
        String dbName = "ia_" + UUID.randomUUID().toString().replace("-", "");
        dbUrl = "jdbc:h2:mem:" + dbName + ";DATABASE_TO_LOWER=TRUE";
        keepAliveConn = DriverManager.getConnection(dbUrl, "sa", "");
        createTables(keepAliveConn);

        mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenAnswer(inv ->
                DriverManager.getConnection(dbUrl, "sa", ""));

        WorkflowUserManager mockWum = mock(WorkflowUserManager.class);
        when(mockWum.getCurrentUsername()).thenReturn("analyst01");

        FormDataDao mockDao = mock(FormDataDao.class);

        ApplicationContext mockCtx = mock(ApplicationContext.class);
        when(mockCtx.getBean("setupDataSource")).thenReturn(mockDs);
        when(mockCtx.getBean("workflowUserManager")).thenReturn(mockWum);
        when(mockCtx.getBean("formDataDao")).thenReturn(mockDao);
        envVarCounter.set(0);
        EnvironmentVariableDao mockEnvDao = mock(EnvironmentVariableDao.class);
        when(mockEnvDao.getIncreasedCounter(anyString(), anyString(), any(AppDefinition.class)))
                .thenAnswer(inv -> (int) envVarCounter.incrementAndGet());
        when(mockEnvDao.getIncreasedCounter(anyString(), anyString(), isNull()))
                .thenAnswer(inv -> (int) envVarCounter.incrementAndGet());
        when(mockCtx.getBean("environmentVariableDao")).thenReturn(mockEnvDao);

        mockedAppUtil = mockStatic(AppUtil.class);
        mockedAppUtil.when(AppUtil::getApplicationContext).thenReturn(mockCtx);
        mockedAppUtil.when(AppUtil::getCurrentAppDefinition).thenReturn(null);

        service = new EnrichmentService();
        service.setDao(mockDao);
        config = incomeAllocationConfig();
    }

    @After
    public void tearDown() throws Exception {
        mockedAppUtil.close();
        if (keepAliveConn != null && !keepAliveConn.isClosed()) keepAliveConn.close();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void setupIncomeScenario(String enrichmentId, String assetId,
                                      String amount, String currency, String fxRate) throws Exception {
        insertEnrichmentRow(keepAliveConn, enrichmentId, fields(
                "status", "enriched",
                "internal_type", "DIV_INCOME",
                "resolved_asset_id", assetId,
                "total_amount", amount,
                "validated_currency", currency,
                "fx_rate_to_eur", fxRate,
                "transaction_date", "2026-03-01",
                "fund_allocation_status", "",
                "processing_notes", ""));
    }

    private void insertLot(String lotId, String customerId, String assetId,
                            String ticker, String direction, String quantity,
                            String allocationDate) throws Exception {
        insertRow(keepAliveConn, "allocationLot", lotId, fields(
                "customerId", customerId,
                "assetId", assetId,
                "assetTicker", ticker,
                "direction", direction,
                "quantity", quantity,
                "allocationDate", allocationDate,
                "sourceEnrichmentId", "SRC-" + lotId));
    }

    private void insertCustomer(String id, String customerId, String displayName) throws Exception {
        insertRow(keepAliveConn, "customer", id, fields(
                "customerId", customerId,
                "displayName", displayName,
                "is_fund", "no"));
    }

    private List<Map<String, String>> readIncomeAllocations(String enrichmentId) throws Exception {
        String sql = "SELECT * FROM app_fd_incomeallocation WHERE c_sourceenrichmentid = ? ORDER BY c_customerid";
        List<Map<String, String>> result = new ArrayList<>();
        try (PreparedStatement ps = keepAliveConn.prepareStatement(sql)) {
            ps.setString(1, enrichmentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.util.Map<String, String> row = new java.util.HashMap<>();
                    java.sql.ResultSetMetaData meta = rs.getMetaData();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnName(i).toLowerCase(), rs.getString(i));
                    }
                    result.add(row);
                }
            }
        }
        return result;
    }

    // ── Basic allocation tests ──────────────────────────────────────────

    @Test
    public void allocateIncome_twoCustomers_proportionalSplit() throws Exception {
        // ADBE-like scenario: 2 customers, 38-day period
        // Customer A: holds 15 shares for full 38 days = 570 share-days
        // Customer B: holds 5 shares for full 38 days = 190 share-days
        // Total share-days: 760. A gets 75%, B gets 25%.
        String eid = "ENR-DIV-001";
        setupIncomeScenario(eid, "ASSET-ADBE", "1000.00", "USD", "0.92");

        // Lots: both bought before period
        insertLot("LOT-A1", "CUST-A", "ASSET-ADBE", "ADBE", "BUY", "15", "2026-01-01");
        insertLot("LOT-B1", "CUST-B", "ASSET-ADBE", "ADBE", "BUY", "5", "2026-01-15");
        insertCustomer("C-A", "CUST-A", "Alice Smith");
        insertCustomer("C-B", "CUST-B", "Bob Jones");

        Map<String, Object> result = service.allocateIncome(
                TABLE, eid, "2026-02-01", "2026-03-11", false, config);

        assertTrue((Boolean) result.get("success"));
        assertEquals("allocated", result.get("allocationStatus"));
        assertEquals(1000.0, (Double) result.get("totalAmount"), 0.01);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocs = (List<Map<String, Object>>) result.get("allocations");
        assertEquals(2, allocs.size());

        // Check total share-days: 15*38 + 5*38 = 570 + 190 = 760
        assertEquals(760.0, (Double) result.get("totalShareDays"), 0.01);

        // Find customer A and B allocations
        Map<String, Object> allocA = allocs.stream()
                .filter(a -> "CUST-A".equals(a.get("customerId"))).findFirst().orElse(null);
        Map<String, Object> allocB = allocs.stream()
                .filter(a -> "CUST-B".equals(a.get("customerId"))).findFirst().orElse(null);
        assertNotNull(allocA);
        assertNotNull(allocB);

        assertEquals(570.0, (Double) allocA.get("shareDays"), 0.01);
        assertEquals(190.0, (Double) allocB.get("shareDays"), 0.01);

        // 570/760 ≈ 0.750000, 190/760 ≈ 0.250000
        assertEquals(0.75, (Double) allocA.get("allocationPct"), 0.001);
        assertEquals(0.25, (Double) allocB.get("allocationPct"), 0.001);

        // 1000 * 0.75 = 750, 1000 * 0.25 = 250
        assertEquals(750.0, (Double) allocA.get("allocatedAmount"), 0.01);
        assertEquals(250.0, (Double) allocB.get("allocatedAmount"), 0.01);

        // Verify records were written
        List<Map<String, String>> iaRows = readIncomeAllocations(eid);
        assertEquals(2, iaRows.size());

        // Verify enrichment status updated
        Map<String, String> enrichment = readRow(keepAliveConn, eid);
        assertEquals("allocated", enrichment.get("c_fund_allocation_status"));
        assertTrue(enrichment.get("c_processing_notes").contains("Income allocated to 2 customers"));
    }

    @Test
    public void allocateIncome_singleCustomer_fullAllocation() throws Exception {
        String eid = "ENR-DIV-002";
        setupIncomeScenario(eid, "ASSET-MSFT", "500.00", "USD", "0.92");

        insertLot("LOT-S1", "CUST-ONLY", "ASSET-MSFT", "MSFT", "BUY", "100", "2026-01-01");
        insertCustomer("C-ONLY", "CUST-ONLY", "Solo Investor");

        Map<String, Object> result = service.allocateIncome(
                TABLE, eid, "2026-02-01", "2026-03-01", false, config);

        assertTrue((Boolean) result.get("success"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocs = (List<Map<String, Object>>) result.get("allocations");
        assertEquals(1, allocs.size());

        Map<String, Object> alloc = allocs.get(0);
        assertEquals(1.0, (Double) alloc.get("allocationPct"), 0.0001);
        assertEquals(500.0, (Double) alloc.get("allocatedAmount"), 0.01);
    }

    @Test
    public void allocateIncome_negativeAmount_divTax() throws Exception {
        String eid = "ENR-TAX-001";
        insertEnrichmentRow(keepAliveConn, eid, fields(
                "status", "enriched",
                "internal_type", "DIV_TAX",
                "resolved_asset_id", "ASSET-AAPL",
                "total_amount", "-150.00",
                "validated_currency", "USD",
                "fx_rate_to_eur", "0.92",
                "transaction_date", "2026-03-01",
                "fund_allocation_status", "",
                "processing_notes", ""));

        insertLot("LOT-T1", "CUST-A", "ASSET-AAPL", "AAPL", "BUY", "10", "2026-01-01");
        insertCustomer("CT-A", "CUST-A", "Alice");

        Map<String, Object> result = service.allocateIncome(
                TABLE, eid, "2026-02-01", "2026-03-01", false, config);

        assertTrue((Boolean) result.get("success"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocs = (List<Map<String, Object>>) result.get("allocations");
        assertEquals(1, allocs.size());
        assertTrue((Double) allocs.get(0).get("allocatedAmount") < 0);
        assertEquals(-150.0, (Double) allocs.get(0).get("allocatedAmount"), 0.01);
    }

    // ── Holdings reconstruction tests ───────────────────────────────────

    @Test
    public void allocateIncome_customerSellsMidPeriod() throws Exception {
        // Customer A holds 100, sells on day 20. Customer B buys 100 on day 20.
        // Period: 2026-02-01 to 2026-03-01 (28 days)
        // A: 100 * 20 = 2000 share-days
        // B: 100 * 8 = 800 share-days (from Feb 21 to Mar 1)
        String eid = "ENR-DIV-SELL";
        setupIncomeScenario(eid, "ASSET-X", "2800.00", "USD", "1.0");

        insertLot("LOT-SA", "CUST-A", "ASSET-X", "X", "BUY", "100", "2026-01-01");
        insertLot("LOT-SA2", "CUST-A", "ASSET-X", "X", "SELL", "100", "2026-02-21");
        insertLot("LOT-SB", "CUST-B", "ASSET-X", "X", "BUY", "100", "2026-02-21");
        insertCustomer("CS-A", "CUST-A", "Seller A");
        insertCustomer("CS-B", "CUST-B", "Buyer B");

        Map<String, Object> result = service.allocateIncome(
                TABLE, eid, "2026-02-01", "2026-03-01", false, config);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocs = (List<Map<String, Object>>) result.get("allocations");
        assertEquals(2, allocs.size());

        Map<String, Object> allocA = allocs.stream()
                .filter(a -> "CUST-A".equals(a.get("customerId"))).findFirst().orElse(null);
        Map<String, Object> allocB = allocs.stream()
                .filter(a -> "CUST-B".equals(a.get("customerId"))).findFirst().orElse(null);

        assertNotNull(allocA);
        assertNotNull(allocB);

        // A: 100 shares * 20 days = 2000
        assertEquals(2000.0, (Double) allocA.get("shareDays"), 0.01);
        // B: 100 shares * 8 days = 800
        assertEquals(800.0, (Double) allocB.get("shareDays"), 0.01);
    }

    @Test
    public void allocateIncome_customerBuysMidPeriod() throws Exception {
        // Period: 2026-02-01 to 2026-03-01 (28 days)
        // Customer buys 50 on Feb 15 → 14 days of holding (Feb 15 to Mar 1)
        String eid = "ENR-DIV-MID";
        setupIncomeScenario(eid, "ASSET-Y", "100.00", "EUR", "1.0");

        insertLot("LOT-M1", "CUST-M", "ASSET-Y", "Y", "BUY", "50", "2026-02-15");
        insertCustomer("CM", "CUST-M", "Mid Buyer");

        Map<String, Object> result = service.allocateIncome(
                TABLE, eid, "2026-02-01", "2026-03-01", false, config);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocs = (List<Map<String, Object>>) result.get("allocations");
        assertEquals(1, allocs.size());

        // 50 shares * 14 days = 700 share-days
        assertEquals(700.0, (Double) allocs.get(0).get("shareDays"), 0.01);
    }

    @Test
    public void allocateIncome_multipleBuysForSameCustomer() throws Exception {
        // Customer has 2 BUY lots, quantities sum
        // Period: 2026-02-01 to 2026-03-01 (28 days)
        // Buy 30 on Jan 1 (before period), buy 20 on Feb 10
        // Share-days: 30 * 28 (full period) + 20 * 19 (Feb 10 to Mar 1) = 840 + 380 = 1220
        // Wait — let me recalculate.
        // Position before period: 30 (from Jan 1 buy)
        // Feb 1 to Feb 10: 30 * 9 = 270
        // Feb 10 event: +20 → position = 50
        // Feb 10 to Mar 1: 50 * 19 = 950
        // Total: 270 + 950 = 1220
        String eid = "ENR-DIV-MULTI";
        setupIncomeScenario(eid, "ASSET-Z", "122.00", "USD", "1.0");

        insertLot("LOT-MB1", "CUST-MB", "ASSET-Z", "Z", "BUY", "30", "2026-01-01");
        insertLot("LOT-MB2", "CUST-MB", "ASSET-Z", "Z", "BUY", "20", "2026-02-10");
        insertCustomer("CMB", "CUST-MB", "Multi Buyer");

        Map<String, Object> result = service.allocateIncome(
                TABLE, eid, "2026-02-01", "2026-03-01", false, config);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocs = (List<Map<String, Object>>) result.get("allocations");
        assertEquals(1, allocs.size());

        assertEquals(1220.0, (Double) allocs.get(0).get("shareDays"), 0.01);
    }

    @Test
    public void allocateIncome_lotsBeforePeriod_contributesToPosition() throws Exception {
        // Buy before period carries position into period
        String eid = "ENR-DIV-BEFORE";
        setupIncomeScenario(eid, "ASSET-PRE", "100.00", "USD", "1.0");

        insertLot("LOT-PRE", "CUST-PRE", "ASSET-PRE", "PRE", "BUY", "10", "2025-06-01");
        insertCustomer("CPRE", "CUST-PRE", "Pre Buyer");

        Map<String, Object> result = service.allocateIncome(
                TABLE, eid, "2026-02-01", "2026-02-11", false, config);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocs = (List<Map<String, Object>>) result.get("allocations");
        assertEquals(1, allocs.size());

        // 10 shares * 10 days = 100
        assertEquals(100.0, (Double) allocs.get(0).get("shareDays"), 0.01);
    }

    @Test
    public void allocateIncome_lotsAfterPeriod_ignored() throws Exception {
        // Buy after period end should not affect calculation
        String eid = "ENR-DIV-AFTER";
        setupIncomeScenario(eid, "ASSET-POST", "200.00", "USD", "1.0");

        insertLot("LOT-POST1", "CUST-P1", "ASSET-POST", "POST", "BUY", "10", "2026-01-01");
        insertLot("LOT-POST2", "CUST-P2", "ASSET-POST", "POST", "BUY", "20", "2026-04-01");
        insertCustomer("CP1", "CUST-P1", "Pre Period");
        insertCustomer("CP2", "CUST-P2", "Post Period");

        Map<String, Object> result = service.allocateIncome(
                TABLE, eid, "2026-02-01", "2026-03-01", false, config);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocs = (List<Map<String, Object>>) result.get("allocations");
        // Only CUST-P1 should have allocations (pre-period buy carries in)
        assertEquals(1, allocs.size());
        assertEquals("CUST-P1", allocs.get(0).get("customerId"));
    }

    // ── Preview (dry-run) tests ─────────────────────────────────────────

    @Test
    public void previewIncomeAllocation_returnsComputedAllocations() throws Exception {
        String eid = "ENR-DIV-PREV";
        setupIncomeScenario(eid, "ASSET-PV", "1000.00", "USD", "0.92");

        insertLot("LOT-PV1", "CUST-PV", "ASSET-PV", "PV", "BUY", "100", "2026-01-01");
        insertCustomer("CPV", "CUST-PV", "Preview User");

        Map<String, Object> result = service.allocateIncome(
                TABLE, eid, "2026-02-01", "2026-03-01", true, config);

        assertTrue((Boolean) result.get("success"));
        assertEquals("preview", result.get("allocationStatus"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocs = (List<Map<String, Object>>) result.get("allocations");
        assertEquals(1, allocs.size());
        assertEquals(1000.0, (Double) allocs.get(0).get("allocatedAmount"), 0.01);
    }

    @Test
    public void previewIncomeAllocation_doesNotWriteRecords() throws Exception {
        String eid = "ENR-DIV-NOWR";
        setupIncomeScenario(eid, "ASSET-NW", "500.00", "USD", "1.0");

        insertLot("LOT-NW1", "CUST-NW", "ASSET-NW", "NW", "BUY", "50", "2026-01-01");
        insertCustomer("CNW", "CUST-NW", "No Write User");

        service.allocateIncome(TABLE, eid, "2026-02-01", "2026-03-01", true, config);

        // Verify no incomeAllocation rows written
        List<Map<String, String>> iaRows = readIncomeAllocations(eid);
        assertEquals(0, iaRows.size());

        // Verify enrichment status NOT updated
        Map<String, String> enrichment = readRow(keepAliveConn, eid);
        assertEquals("", enrichment.get("c_fund_allocation_status"));
    }

    // ── Validation error tests ──────────────────────────────────────────

    @Test(expected = IllegalArgumentException.class)
    public void allocateIncome_wrongType_throws() throws Exception {
        String eid = "ENR-DIV-WT";
        insertEnrichmentRow(keepAliveConn, eid, fields(
                "status", "enriched",
                "internal_type", "EQ_BUY",
                "resolved_asset_id", "ASSET-X",
                "total_amount", "100",
                "validated_currency", "USD",
                "fx_rate_to_eur", "1.0",
                "transaction_date", "2026-03-01",
                "fund_allocation_status", "",
                "processing_notes", ""));

        service.allocateIncome(TABLE, eid, "2026-02-01", "2026-03-01", false, config);
    }

    @Test(expected = IllegalArgumentException.class)
    public void allocateIncome_wrongStatus_throws() throws Exception {
        String eid = "ENR-DIV-WS";
        insertEnrichmentRow(keepAliveConn, eid, fields(
                "status", "confirmed",
                "internal_type", "DIV_INCOME",
                "resolved_asset_id", "ASSET-X",
                "total_amount", "100",
                "validated_currency", "USD",
                "fx_rate_to_eur", "1.0",
                "transaction_date", "2026-03-01",
                "fund_allocation_status", "",
                "processing_notes", ""));

        service.allocateIncome(TABLE, eid, "2026-02-01", "2026-03-01", false, config);
    }

    @Test(expected = IllegalStateException.class)
    public void allocateIncome_alreadyAllocated_throws() throws Exception {
        String eid = "ENR-DIV-AA";
        insertEnrichmentRow(keepAliveConn, eid, fields(
                "status", "enriched",
                "internal_type", "DIV_INCOME",
                "resolved_asset_id", "ASSET-X",
                "total_amount", "100",
                "validated_currency", "USD",
                "fx_rate_to_eur", "1.0",
                "transaction_date", "2026-03-01",
                "fund_allocation_status", "allocated",
                "processing_notes", ""));

        service.allocateIncome(TABLE, eid, "2026-02-01", "2026-03-01", false, config);
    }

    @Test(expected = IllegalArgumentException.class)
    public void allocateIncome_noAsset_throws() throws Exception {
        String eid = "ENR-DIV-NA";
        insertEnrichmentRow(keepAliveConn, eid, fields(
                "status", "enriched",
                "internal_type", "DIV_INCOME",
                "resolved_asset_id", "",
                "total_amount", "100",
                "validated_currency", "USD",
                "fx_rate_to_eur", "1.0",
                "transaction_date", "2026-03-01",
                "fund_allocation_status", "",
                "processing_notes", ""));

        service.allocateIncome(TABLE, eid, "2026-02-01", "2026-03-01", false, config);
    }

    @Test(expected = IllegalArgumentException.class)
    public void allocateIncome_periodEndBeforeStart_throws() throws Exception {
        String eid = "ENR-DIV-PD";
        setupIncomeScenario(eid, "ASSET-PD", "100.00", "USD", "1.0");

        service.allocateIncome(TABLE, eid, "2026-03-01", "2026-02-01", false, config);
    }

    @Test(expected = IllegalArgumentException.class)
    public void allocateIncome_noHoldings_throws() throws Exception {
        String eid = "ENR-DIV-NH";
        setupIncomeScenario(eid, "ASSET-NOHLD", "100.00", "USD", "1.0");
        // No lots inserted for ASSET-NOHLD

        service.allocateIncome(TABLE, eid, "2026-02-01", "2026-03-01", false, config);
    }

    @Test(expected = RecordNotFoundException.class)
    public void allocateIncome_recordNotFound_throws() throws Exception {
        service.allocateIncome(TABLE, "NONEXISTENT-ID", "2026-02-01", "2026-03-01", false, config);
    }

    // ── Rounding test ───────────────────────────────────────────────────

    @Test
    public void allocateIncome_roundingRemainder_adjustedToLargest() throws Exception {
        // Three customers: create a scenario where rounding causes sum < totalAmount
        // Amount = 100.00, three equal holdings → each gets 33.333333
        // Rounded: 33.333333 * 3 = 99.999999, remainder = 0.000001
        String eid = "ENR-DIV-ROUND";
        setupIncomeScenario(eid, "ASSET-RND", "100.00", "USD", "1.0");

        insertLot("LOT-R1", "CUST-R1", "ASSET-RND", "RND", "BUY", "10", "2026-01-01");
        insertLot("LOT-R2", "CUST-R2", "ASSET-RND", "RND", "BUY", "10", "2026-01-01");
        insertLot("LOT-R3", "CUST-R3", "ASSET-RND", "RND", "BUY", "10", "2026-01-01");
        insertCustomer("CR1", "CUST-R1", "Round A");
        insertCustomer("CR2", "CUST-R2", "Round B");
        insertCustomer("CR3", "CUST-R3", "Round C");

        Map<String, Object> result = service.allocateIncome(
                TABLE, eid, "2026-02-01", "2026-03-01", false, config);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocs = (List<Map<String, Object>>) result.get("allocations");
        assertEquals(3, allocs.size());

        // Sum of allocated amounts should equal total amount exactly
        double sum = 0;
        for (Map<String, Object> a : allocs) {
            sum += (Double) a.get("allocatedAmount");
        }
        assertEquals(100.0, sum, 0.000001);
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    public void allocateIncome_bondInt_eligibleType() throws Exception {
        String eid = "ENR-BOND-INT";
        insertEnrichmentRow(keepAliveConn, eid, fields(
                "status", "enriched",
                "internal_type", "BOND_INT",
                "resolved_asset_id", "ASSET-BOND",
                "total_amount", "250.00",
                "validated_currency", "EUR",
                "fx_rate_to_eur", "1.0",
                "transaction_date", "2026-03-01",
                "fund_allocation_status", "",
                "processing_notes", ""));

        insertLot("LOT-BI", "CUST-BI", "ASSET-BOND", "BOND1", "BUY", "10", "2026-01-01");
        insertCustomer("CBI", "CUST-BI", "Bond Investor");

        Map<String, Object> result = service.allocateIncome(
                TABLE, eid, "2026-02-01", "2026-03-01", false, config);

        assertTrue((Boolean) result.get("success"));
        assertEquals(250.0, (Double) result.get("totalAmount"), 0.01);
    }

    @Test
    public void allocateIncome_zeroAmount_allocatesZeros() throws Exception {
        String eid = "ENR-DIV-ZERO";
        setupIncomeScenario(eid, "ASSET-ZERO", "0", "USD", "1.0");

        insertLot("LOT-Z1", "CUST-Z", "ASSET-ZERO", "ZERO", "BUY", "100", "2026-01-01");
        insertCustomer("CZ", "CUST-Z", "Zero Amount");

        Map<String, Object> result = service.allocateIncome(
                TABLE, eid, "2026-02-01", "2026-03-01", false, config);

        assertTrue((Boolean) result.get("success"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocs = (List<Map<String, Object>>) result.get("allocations");
        assertEquals(1, allocs.size());
        assertEquals(0.0, (Double) allocs.get(0).get("allocatedAmount"), 0.0001);
    }
}
