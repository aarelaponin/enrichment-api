package org.joget.gam.enrichment.service;

import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.workflow.model.service.WorkflowUserManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.joget.gam.enrichment.service.JdbcTestHelper.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for EnrichmentService.approveConfirmedRecords() — the maker-checker checker step.
 * The checker (mocked current user "analyst01") approves confirmed records confirmed by
 * someone else, can't approve their own, and skips already-approved records.
 */
public class EnrichmentServiceApproveTest {

    private Connection keepAliveConn;
    private MockedStatic<AppUtil> mockedAppUtil;
    private EnrichmentService service;
    private ValidationConfig config;
    private String dbUrl;

    @Before
    public void setUp() throws Exception {
        String dbName = "approve_" + UUID.randomUUID().toString().replace("-", "");
        dbUrl = "jdbc:h2:mem:" + dbName + ";DATABASE_TO_LOWER=TRUE";
        keepAliveConn = DriverManager.getConnection(dbUrl, "sa", "");
        createTables(keepAliveConn);

        DataSource mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenAnswer(inv -> DriverManager.getConnection(dbUrl, "sa", ""));

        WorkflowUserManager mockWum = mock(WorkflowUserManager.class);
        when(mockWum.getCurrentUsername()).thenReturn("analyst01"); // the checker

        FormDataDao mockDao = mock(FormDataDao.class);

        ApplicationContext mockCtx = mock(ApplicationContext.class);
        when(mockCtx.getBean("setupDataSource")).thenReturn(mockDs);
        when(mockCtx.getBean("workflowUserManager")).thenReturn(mockWum);
        when(mockCtx.getBean("formDataDao")).thenReturn(mockDao);

        mockedAppUtil = mockStatic(AppUtil.class);
        mockedAppUtil.when(AppUtil::getApplicationContext).thenReturn(mockCtx);

        service = new EnrichmentService();
        service.setDao(mockDao);
        config = confirmConfig();
    }

    @After
    public void tearDown() throws Exception {
        mockedAppUtil.close();
        if (keepAliveConn != null && !keepAliveConn.isClosed()) keepAliveConn.close();
    }

    @Test
    public void approvesRecordConfirmedBySomeoneElse() throws Exception {
        insertEnrichmentRow(keepAliveConn, "A1", fields(
                "status", "confirmed", "confirmed_by", "maker99",
                "internal_type", "FX", "debit_credit", "D"));

        Map<String, Object> result = service.approveConfirmedRecords(TABLE, config);

        assertEquals(1, result.get("approved"));
        assertEquals(0, result.get("skippedOwn"));
        assertEquals("analyst01", result.get("approver"));

        Map<String, String> row = readRow(keepAliveConn, "A1");
        assertEquals("analyst01", row.get("c_approved_by"));
        assertNotNull(row.get("c_approved_at"));
        assertEquals("confirmed", row.get("c_status")); // status unchanged
    }

    @Test
    public void doesNotApproveOwnConfirmation() throws Exception {
        insertEnrichmentRow(keepAliveConn, "A2", fields(
                "status", "confirmed", "confirmed_by", "analyst01",
                "internal_type", "FX", "debit_credit", "D"));

        Map<String, Object> result = service.approveConfirmedRecords(TABLE, config);

        assertEquals(0, result.get("approved"));
        assertEquals(1, result.get("skippedOwn"));

        Map<String, String> row = readRow(keepAliveConn, "A2");
        assertNull(row.get("c_approved_by"));
    }

    @Test
    public void skipsAlreadyApproved() throws Exception {
        insertEnrichmentRow(keepAliveConn, "A3", fields(
                "status", "confirmed", "confirmed_by", "maker99", "approved_by", "boss01",
                "internal_type", "FX", "debit_credit", "D"));

        Map<String, Object> result = service.approveConfirmedRecords(TABLE, config);

        assertEquals(0, result.get("approved"));
        assertEquals(1, result.get("alreadyApproved"));

        Map<String, String> row = readRow(keepAliveConn, "A3");
        assertEquals("boss01", row.get("c_approved_by")); // unchanged
    }

    @Test
    public void writesAuditEntryOnApproval() throws Exception {
        insertEnrichmentRow(keepAliveConn, "A4", fields(
                "status", "confirmed", "confirmed_by", "maker99",
                "internal_type", "FX", "debit_credit", "D"));

        service.approveConfirmedRecords(TABLE, config);

        List<Map<String, String>> audits = readAuditEntries(keepAliveConn, "A4");
        assertEquals(1, audits.size());
        assertEquals("analyst01", audits.get(0).get("c_triggered_by"));
        assertEquals("confirmed", audits.get(0).get("c_to_status"));
    }

    @Test
    public void mixedBatch() throws Exception {
        insertEnrichmentRow(keepAliveConn, "B1", fields(
                "status", "confirmed", "confirmed_by", "maker99",
                "internal_type", "FX", "debit_credit", "D")); // approvable
        insertEnrichmentRow(keepAliveConn, "B2", fields(
                "status", "confirmed", "confirmed_by", "analyst01",
                "internal_type", "FX", "debit_credit", "D")); // own -> skip
        insertEnrichmentRow(keepAliveConn, "B3", fields(
                "status", "ready", "confirmed_by", "maker99",
                "internal_type", "FX", "debit_credit", "D")); // not confirmed -> not scanned

        Map<String, Object> result = service.approveConfirmedRecords(TABLE, config);

        assertEquals(1, result.get("approved"));
        assertEquals(1, result.get("skippedOwn"));
        assertEquals(2, result.get("scanned")); // only the two confirmed rows
    }
}
