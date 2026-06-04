package org.joget.gam.enrichment.service;

import org.joget.apps.app.dao.EnvironmentVariableDao;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.workflow.model.service.WorkflowUserManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.joget.gam.enrichment.service.JdbcTestHelper.*;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * H2 integration tests for automatic trade allocation (D-4). Drives the full path through the
 * proven {@code allocateTrade}: a BUY splits across investors by capital share, a SELL by current
 * holdings, and a trade with no capital basis is reported (not silently allocated).
 */
public class EnrichmentServiceAutoAllocateJdbcTest {

    private static final AtomicLong envVarCounter = new AtomicLong(0);
    private Connection keepAliveConn;
    private MockedStatic<AppUtil> mockedAppUtil;
    private EnrichmentService service;
    private ValidationConfig config;
    private String dbUrl;

    @Before
    public void setUp() throws Exception {
        dbUrl = "jdbc:h2:mem:autoalloc_" + UUID.randomUUID().toString().replace("-", "") + ";DATABASE_TO_LOWER=TRUE";
        keepAliveConn = DriverManager.getConnection(dbUrl, "sa", "");
        createTables(keepAliveConn);

        DataSource mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenAnswer(inv -> DriverManager.getConnection(dbUrl, "sa", ""));
        WorkflowUserManager mockWum = mock(WorkflowUserManager.class);
        when(mockWum.getCurrentUsername()).thenReturn("analyst01");
        FormDataDao mockDao = mock(FormDataDao.class);
        doAnswer(inv -> {
            JdbcTestHelper.saveFormRows(keepAliveConn, inv.getArgument(1), inv.getArgument(2));
            return null;
        }).when(mockDao).saveOrUpdate(any(), any(), any());
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
        config = allocationConfig();
    }

    @After
    public void tearDown() throws Exception {
        if (mockedAppUtil != null) mockedAppUtil.close();
        if (keepAliveConn != null) keepAliveConn.close();
    }

    private void product() throws Exception {
        insertRow(keepAliveConn, "product", "PRD", fields(
                "productId", "PRD-DEFAULT-INV", "name", "Default Pool",
                "businessLine", "Investment", "status", "Active", "effectiveFrom", "2024-01-01"));
    }

    private void customer(String cust) throws Exception {
        insertRow(keepAliveConn, "customer", cust, fields(
                "customerId", cust, "displayName", cust, "is_fund", "no"));
    }

    private void investorWithCapital(String cust, String deposit) throws Exception {
        customer(cust);
        insertRow(keepAliveConn, "customerProductHolding", "H-" + cust, fields(
                "holdingId", "H-" + cust, "customerId", cust, "productId", "PRD-DEFAULT-INV",
                "role", "Investor", "status", "Active", "effectiveFrom", "2024-01-01"));
        if (deposit != null) {
            insertRow(keepAliveConn, "customer_deposit", "D-" + cust, fields(
                    "depositId", "D-" + cust, "customerId", cust, "amount", deposit,
                    "currency", "EUR", "valueDate", "2024-01-01", "status", "Posted"));
        }
    }

    private void position(String cust, String qty, String cost) throws Exception {
        insertRow(keepAliveConn, "portfolioPosition", "PP-" + cust, fields(
                "customerId", cust, "assetId", "AST1", "assetTicker", "X", "quantity", qty,
                "totalCostBasis", cost, "totalCostBasisEur", cost, "averageCostPerUnit", "10",
                "currency", "EUR", "status", "active"));
    }

    private void trade(String enrId, String secuId, String type, String qty, String date) throws Exception {
        insertEnrichmentRow(keepAliveConn, enrId, fields(
                "status", "paired", "internal_type", type, "source_trx_id", secuId,
                "resolved_asset_id", "AST1", "transaction_date", date,
                "pair_id", "P1", "has_fee", "no", "fund_allocation_status", "", "processing_notes", ""));
        insertRow(keepAliveConn, "secu_total_trx", secuId, fields(
                "quantity", qty, "price", "10", "fee", "0", "amount", "1000",
                "ticker", "X", "currency", "EUR", "enrichment_id", enrId));
    }

    private BigDecimal positionQty(String cust) throws Exception {
        try (Statement st = keepAliveConn.createStatement();
             ResultSet rs = st.executeQuery("SELECT c_quantity FROM app_fd_portfolioPosition WHERE c_customerId='" + cust + "'")) {
            return rs.next() ? new BigDecimal(rs.getString(1)) : null;
        }
    }

    @Test
    public void buyAllocatesByCapitalShare() throws Exception {
        product();
        investorWithCapital("A", "6000");
        investorWithCapital("B", "4000");
        trade("ENR-BUY", "SECU-B", "EQ_BUY", "100", "2024-06-01");

        Map<String, Object> r = service.autoAllocateTrade(TABLE, "ENR-BUY", config);

        assertEquals("allocated", r.get("outcome"));
        assertEquals(2, r.get("investorsAllocated"));
        assertEquals(0, new BigDecimal("60").compareTo(positionQty("A")));
        assertEquals(0, new BigDecimal("40").compareTo(positionQty("B")));
    }

    @Test
    public void sellAllocatesByHoldings() throws Exception {
        product();
        customer("A");
        customer("B");
        position("A", "60", "600");
        position("B", "40", "400");
        trade("ENR-SELL", "SECU-S", "EQ_SELL", "50", "2024-07-01");

        Map<String, Object> r = service.autoAllocateTrade(TABLE, "ENR-SELL", config);

        assertEquals("allocated", r.get("outcome"));
        assertEquals(2, r.get("investorsAllocated"));
        // sold 50 split 60:40 -> 30 from A, 20 from B; positions reduced
        assertEquals(0, new BigDecimal("30").compareTo(positionQty("A")));
        assertEquals(0, new BigDecimal("20").compareTo(positionQty("B")));
    }

    @Test
    public void buyWithoutCapitalBasisIsReportedNotAllocated() throws Exception {
        product();
        investorWithCapital("A", null); // holding but no deposit -> no capital basis
        trade("ENR-BUY2", "SECU-B2", "EQ_BUY", "100", "2024-06-01");

        Map<String, Object> r = service.autoAllocateTrade(TABLE, "ENR-BUY2", config);

        assertEquals("no_capital_basis", r.get("outcome"));
        assertEquals(0, r.get("investorsAllocated"));
    }
}
