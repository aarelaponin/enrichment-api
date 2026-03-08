package org.joget.gam.enrichment.service;

import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.workflow.model.service.WorkflowUserManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.joget.gam.enrichment.service.JdbcTestHelper.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for EnrichmentService.confirmRecords() — uses MockedStatic + H2.
 */
public class EnrichmentServiceConfirmTest {

    private Connection keepAliveConn;
    private DataSource mockDs;
    private FormDataDao mockDao;
    private MockedStatic<AppUtil> mockedAppUtil;
    private EnrichmentService service;
    private ValidationConfig config;
    private String dbUrl;

    @Before
    public void setUp() throws Exception {
        String dbName = "confirm_" + UUID.randomUUID().toString().replace("-", "");
        dbUrl = "jdbc:h2:mem:" + dbName + ";DATABASE_TO_LOWER=TRUE";
        keepAliveConn = DriverManager.getConnection(dbUrl, "sa", "");
        createTables(keepAliveConn);

        // Mock DataSource to return H2 connections
        mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenAnswer(inv ->
                DriverManager.getConnection(dbUrl, "sa", ""));

        // Mock WorkflowUserManager
        WorkflowUserManager mockWum = mock(WorkflowUserManager.class);
        when(mockWum.getCurrentUsername()).thenReturn("analyst01");

        // Mock FormDataDao
        mockDao = mock(FormDataDao.class);

        // Mock AppUtil.getApplicationContext()
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
    public void confirmRecords_transitionsToConfirmed() throws Exception {
        // Insert a ready record into H2
        insertEnrichmentRow(keepAliveConn, "E001", fields(
                "status", "ready", "customer_code", "CUST-1",
                "internal_type", "FX", "debit_credit", "D"));

        // Mock FormDataDao.load() for pre-validation
        FormRow readyRow = new FormRow();
        readyRow.setId("E001");
        readyRow.setProperty("status", "ready");
        readyRow.setProperty("customer_code", "CUST-1");
        readyRow.setProperty("internal_type", "FX");
        readyRow.setProperty("debit_credit", "D");
        when(mockDao.load(isNull(), eq(TABLE), eq("E001"))).thenReturn(readyRow);

        Map<String, Object> result = service.confirmRecords(TABLE,
                Collections.singletonList("E001"), false, config);

        assertEquals(1, result.get("confirmed"));

        // Verify DB state
        Map<String, String> row = readRow(keepAliveConn, "E001");
        assertEquals("confirmed", row.get("c_status"));
    }

    @Test
    public void confirmRecords_setsConfirmedByToCurrentUser() throws Exception {
        insertEnrichmentRow(keepAliveConn, "E002", fields(
                "status", "ready", "customer_code", "C1",
                "internal_type", "FX", "debit_credit", "D"));

        FormRow readyRow = new FormRow();
        readyRow.setId("E002");
        readyRow.setProperty("status", "ready");
        readyRow.setProperty("customer_code", "C1");
        readyRow.setProperty("internal_type", "FX");
        readyRow.setProperty("debit_credit", "D");
        when(mockDao.load(isNull(), eq(TABLE), eq("E002"))).thenReturn(readyRow);

        service.confirmRecords(TABLE, Collections.singletonList("E002"), false, config);

        Map<String, String> row = readRow(keepAliveConn, "E002");
        assertEquals("analyst01", row.get("c_confirmed_by"));
        assertNotNull(row.get("c_confirmed_at"));
    }

    @Test
    public void confirmRecords_writesAuditEntry() throws Exception {
        insertEnrichmentRow(keepAliveConn, "E003", fields(
                "status", "ready", "customer_code", "C1",
                "internal_type", "FX", "debit_credit", "D"));

        FormRow readyRow = new FormRow();
        readyRow.setId("E003");
        readyRow.setProperty("status", "ready");
        readyRow.setProperty("customer_code", "C1");
        readyRow.setProperty("internal_type", "FX");
        readyRow.setProperty("debit_credit", "D");
        when(mockDao.load(isNull(), eq(TABLE), eq("E003"))).thenReturn(readyRow);

        service.confirmRecords(TABLE, Collections.singletonList("E003"), false, config);

        List<Map<String, String>> audits = readAuditEntries(keepAliveConn, "E003");
        assertEquals(1, audits.size());
        assertEquals("ENRICHMENT", audits.get(0).get("c_entity_type"));
        assertEquals("ready", audits.get(0).get("c_from_status"));
        assertEquals("confirmed", audits.get(0).get("c_to_status"));
        assertEquals("analyst01", audits.get(0).get("c_triggered_by"));
    }

    @Test
    public void confirmRecords_setsModifiedBy() throws Exception {
        insertEnrichmentRow(keepAliveConn, "E004", fields(
                "status", "ready", "customer_code", "C1",
                "internal_type", "FX", "debit_credit", "D"));

        FormRow readyRow = new FormRow();
        readyRow.setId("E004");
        readyRow.setProperty("status", "ready");
        readyRow.setProperty("customer_code", "C1");
        readyRow.setProperty("internal_type", "FX");
        readyRow.setProperty("debit_credit", "D");
        when(mockDao.load(isNull(), eq(TABLE), eq("E004"))).thenReturn(readyRow);

        service.confirmRecords(TABLE, Collections.singletonList("E004"), false, config);

        Map<String, String> row = readRow(keepAliveConn, "E004");
        assertEquals("analyst01", row.get("modifiedby"));
    }

    @Test
    public void confirmRecords_skipsNonReadyRecords() throws Exception {
        insertEnrichmentRow(keepAliveConn, "E005", fields(
                "status", "enriched", "customer_code", "C1",
                "internal_type", "FX", "debit_credit", "D"));

        FormRow enrichedRow = new FormRow();
        enrichedRow.setId("E005");
        enrichedRow.setProperty("status", "enriched");
        when(mockDao.load(isNull(), eq(TABLE), eq("E005"))).thenReturn(enrichedRow);

        Map<String, Object> result = service.confirmRecords(TABLE,
                Collections.singletonList("E005"), false, config);

        assertEquals(0, result.get("confirmed"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skipped = (List<Map<String, Object>>) result.get("skipped");
        assertEquals(1, skipped.size());
        assertEquals("E005", skipped.get(0).get("id"));

        // DB unchanged
        Map<String, String> row = readRow(keepAliveConn, "E005");
        assertEquals("enriched", row.get("c_status"));
    }

    @Test
    public void confirmRecords_validationFailure() throws Exception {
        // Missing required field customer_code
        insertEnrichmentRow(keepAliveConn, "E006", fields(
                "status", "ready", "internal_type", "FX", "debit_credit", "D"));

        FormRow readyRow = new FormRow();
        readyRow.setId("E006");
        readyRow.setProperty("status", "ready");
        readyRow.setProperty("internal_type", "FX");
        readyRow.setProperty("debit_credit", "D");
        // customer_code not set → validation should fail
        when(mockDao.load(isNull(), eq(TABLE), eq("E006"))).thenReturn(readyRow);

        Map<String, Object> result = service.confirmRecords(TABLE,
                Collections.singletonList("E006"), false, config);

        assertEquals(0, result.get("confirmed"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> valErrors = (List<Map<String, Object>>) result.get("validationErrors");
        assertEquals(1, valErrors.size());
        assertEquals("E006", valErrors.get(0).get("id"));
    }

    @Test
    public void confirmRecords_multipleTogether() throws Exception {
        for (String id : Arrays.asList("E007", "E008", "E009")) {
            insertEnrichmentRow(keepAliveConn, id, fields(
                    "status", "ready", "customer_code", "C1",
                    "internal_type", "FX", "debit_credit", "D",
                    "statement_id", "STMT-1"));

            FormRow row = new FormRow();
            row.setId(id);
            row.setProperty("status", "ready");
            row.setProperty("customer_code", "C1");
            row.setProperty("internal_type", "FX");
            row.setProperty("debit_credit", "D");
            row.setProperty("statement_id", "STMT-1");
            when(mockDao.load(isNull(), eq(TABLE), eq(id))).thenReturn(row);
        }

        Map<String, Object> result = service.confirmRecords(TABLE,
                Arrays.asList("E007", "E008", "E009"), false, config);

        assertEquals(3, result.get("confirmed"));

        // All should be confirmed in DB
        for (String id : Arrays.asList("E007", "E008", "E009")) {
            Map<String, String> row = readRow(keepAliveConn, id);
            assertEquals("confirmed", row.get("c_status"));
        }

        // All should have audit entries
        for (String id : Arrays.asList("E007", "E008", "E009")) {
            List<Map<String, String>> audits = readAuditEntries(keepAliveConn, id);
            assertFalse("Audit should exist for " + id, audits.isEmpty());
        }
    }
}
