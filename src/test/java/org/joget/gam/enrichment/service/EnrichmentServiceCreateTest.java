package org.joget.gam.enrichment.service;

import org.joget.apps.app.service.AppUtil;
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
 * Tests for EnrichmentService.createRecord() — uses MockedStatic + H2.
 */
public class EnrichmentServiceCreateTest {

    private Connection keepAliveConn;
    private MockedStatic<AppUtil> mockedAppUtil;
    private EnrichmentService service;
    private String dbUrl;

    @Before
    public void setUp() throws Exception {
        String dbName = "create_" + UUID.randomUUID().toString().replace("-", "");
        dbUrl = "jdbc:h2:mem:" + dbName + ";DATABASE_TO_LOWER=TRUE";
        keepAliveConn = DriverManager.getConnection(dbUrl, "sa", "");
        createTables(keepAliveConn);

        DataSource mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenAnswer(inv ->
                DriverManager.getConnection(dbUrl, "sa", ""));

        WorkflowUserManager mockWum = mock(WorkflowUserManager.class);
        when(mockWum.getCurrentUsername()).thenReturn("analyst01");

        ApplicationContext mockCtx = mock(ApplicationContext.class);
        when(mockCtx.getBean("setupDataSource")).thenReturn(mockDs);
        when(mockCtx.getBean("workflowUserManager")).thenReturn(mockWum);

        mockedAppUtil = mockStatic(AppUtil.class);
        mockedAppUtil.when(AppUtil::getApplicationContext).thenReturn(mockCtx);

        service = new EnrichmentService();
    }

    @After
    public void tearDown() throws Exception {
        mockedAppUtil.close();
        if (keepAliveConn != null && !keepAliveConn.isClosed()) keepAliveConn.close();
    }

    private Map<String, String> minimalFields() {
        return fields(
                "internal_type", "FX",
                "debit_credit", "D",
                "total_amount", "1000.00",
                "validated_currency", "EUR",
                "transaction_date", "2025-06-15",
                "statement_id", "STMT-1"
        );
    }

    @Test
    public void createRecord_insertsWithCorrectFields() throws Exception {
        Map<String, String> f = minimalFields();
        f.put("description", "Test accrual");

        Map<String, Object> result = service.createRecord(TABLE, f);

        assertNotNull(result.get("id"));
        assertEquals("enriched", result.get("status"));
        assertEquals(0, result.get("version"));

        // Verify in DB
        String id = (String) result.get("id");
        Map<String, String> row = readRow(keepAliveConn, id);
        assertNotNull(row);
        assertEquals("enriched", row.get("c_status"));
        assertEquals("0", row.get("c_version"));
        assertEquals("manual", row.get("c_source_tp"));
        assertEquals("FX", row.get("c_internal_type"));
        assertEquals("D", row.get("c_debit_credit"));
        assertEquals("Test accrual", row.get("c_description"));
    }

    @Test
    public void createRecord_enforcesSourceTpManual() throws Exception {
        Map<String, String> f = minimalFields();
        f.put("source_tp", "bank_feed"); // caller tries to override

        Map<String, Object> result = service.createRecord(TABLE, f);

        String id = (String) result.get("id");
        Map<String, String> row = readRow(keepAliveConn, id);
        assertEquals("manual", row.get("c_source_tp"));
    }

    @Test
    public void createRecord_stripsProtectedFields() throws Exception {
        Map<String, String> f = minimalFields();
        f.put("id", "CUSTOM-ID");
        f.put("status", "confirmed");
        f.put("version", "99");
        f.put("confirmed_by", "admin");

        Map<String, Object> result = service.createRecord(TABLE, f);

        // ID should be generated, not the custom one
        assertNotEquals("CUSTOM-ID", result.get("id"));
        assertEquals("enriched", result.get("status"));
        assertEquals(0, result.get("version"));
    }

    @Test
    public void createRecord_missingMandatory_throws() {
        // Missing transaction_date and statement_id
        Map<String, String> f = fields(
                "internal_type", "FX",
                "debit_credit", "D",
                "total_amount", "1000"
        );

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.createRecord(TABLE, f));

        assertTrue(e.getMessage().contains("transaction_date"));
        assertTrue(e.getMessage().contains("statement_id"));
        assertTrue(e.getMessage().contains("validated_currency"));
    }

    @Test
    public void createRecord_writesAudit() throws Exception {
        Map<String, Object> result = service.createRecord(TABLE, minimalFields());

        String id = (String) result.get("id");
        List<Map<String, String>> audits = readAuditEntries(keepAliveConn, id);
        assertEquals(1, audits.size());
        assertEquals("null", audits.get(0).get("c_from_status"));
        assertEquals("enriched", audits.get(0).get("c_to_status"));
        assertEquals("ENRICHMENT", audits.get(0).get("c_entity_type"));
        assertEquals("analyst01", audits.get(0).get("c_triggered_by"));
    }

    @Test
    public void createRecord_setsCreatedBy() throws Exception {
        Map<String, Object> result = service.createRecord(TABLE, minimalFields());

        String id = (String) result.get("id");
        Map<String, String> row = readRow(keepAliveConn, id);
        assertEquals("analyst01", row.get("createdby"));
        assertEquals("analyst01", row.get("modifiedby"));
    }

    @Test
    public void createRecord_filtersNonEditableFields() throws Exception {
        Map<String, String> f = minimalFields();
        f.put("statement_id", "STMT-1"); // mandatory but not in EDITABLE_FIELDS — will be stripped
        // statement_id is not in EDITABLE_FIELDS, so it gets filtered out after validation

        // This tests that mandatory validation happens BEFORE the EDITABLE_FIELDS filter
        Map<String, Object> result = service.createRecord(TABLE, f);
        assertNotNull(result.get("id"));
    }

    @Test
    public void createRecord_acceptsWorkspaceFields() throws Exception {
        Map<String, String> f = minimalFields();
        f.put("loan_id", "LOAN-001");
        f.put("loan_direction", "LENDER");
        f.put("gl_debit_override", "1234");
        f.put("gl_credit_override", "5678");

        Map<String, Object> result = service.createRecord(TABLE, f);

        String id = (String) result.get("id");
        Map<String, String> row = readRow(keepAliveConn, id);
        assertEquals("LOAN-001", row.get("c_loan_id"));
        assertEquals("LENDER", row.get("c_loan_direction"));
        assertEquals("1234", row.get("c_gl_debit_override"));
        assertEquals("5678", row.get("c_gl_credit_override"));
    }
}
