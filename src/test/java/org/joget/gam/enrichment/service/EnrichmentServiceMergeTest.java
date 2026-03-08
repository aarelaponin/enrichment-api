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
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.joget.gam.enrichment.service.JdbcTestHelper.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for EnrichmentService.mergeRecords() — uses MockedStatic + H2.
 */
public class EnrichmentServiceMergeTest {

    private Connection keepAliveConn;
    private MockedStatic<AppUtil> mockedAppUtil;
    private EnrichmentService service;
    private ValidationConfig config;
    private String dbUrl;

    @Before
    public void setUp() throws Exception {
        String dbName = "merge_" + UUID.randomUUID().toString().replace("-", "");
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
        config = splitMergeConfig();
    }

    @After
    public void tearDown() throws Exception {
        mockedAppUtil.close();
        if (keepAliveConn != null && !keepAliveConn.isClosed()) keepAliveConn.close();
    }

    private void insertSource(String id, String status, String amount,
                                String fee, String total) throws SQLException {
        insertEnrichmentRow(keepAliveConn, id, fields(
                "status", status,
                "original_amount", amount,
                "fee_amount", fee,
                "total_amount", total,
                "base_amount_eur", total,
                "fx_rate_to_eur", "1.0",
                "customer_code", "CUST-1",
                "statement_id", "STMT-1",
                "validated_currency", "EUR",
                "debit_credit", "D",
                "internal_type", "FX",
                "transaction_date", "2025-01-15",
                "description", "Source transaction"));
    }

    @Test
    public void mergeRecords_createsNewAndSupersedesSources() throws Exception {
        insertSource("M001", "enriched", "500", "10", "510");
        insertSource("M002", "enriched", "300", "5", "305");

        Map<String, Object> result = service.mergeRecords(TABLE,
                Arrays.asList("M001", "M002"), null, config);

        String mergedId = (String) result.get("mergedId");
        assertNotNull(mergedId);

        // Sources superseded
        assertEquals("superseded", readRow(keepAliveConn, "M001").get("c_status"));
        assertEquals("superseded", readRow(keepAliveConn, "M002").get("c_status"));

        // Merged record created with enriched status
        Map<String, String> merged = readRow(keepAliveConn, mergedId);
        assertNotNull(merged);
        assertEquals("enriched", merged.get("c_status"));
    }

    @Test
    public void mergeRecords_sumsAmounts() throws Exception {
        insertSource("M003", "enriched", "500", "10", "510");
        insertSource("M004", "enriched", "300", "5", "305");

        Map<String, Object> result = service.mergeRecords(TABLE,
                Arrays.asList("M003", "M004"), null, config);

        assertEquals(800.0, (Double) result.get("original_amount"), 0.001);
        assertEquals(15.0, (Double) result.get("fee_amount"), 0.001);
        assertEquals(815.0, (Double) result.get("total_amount"), 0.001);

        String mergedId = (String) result.get("mergedId");
        Map<String, String> merged = readRow(keepAliveConn, mergedId);
        assertEquals("800", merged.get("c_original_amount"));
        assertEquals("15", merged.get("c_fee_amount"));
        assertEquals("815", merged.get("c_total_amount"));
    }

    @Test
    public void mergeRecords_setsCreatedByOnMergedRecord() throws Exception {
        insertSource("M005", "enriched", "500", "10", "510");
        insertSource("M006", "enriched", "300", "5", "305");

        Map<String, Object> result = service.mergeRecords(TABLE,
                Arrays.asList("M005", "M006"), null, config);

        String mergedId = (String) result.get("mergedId");
        Map<String, String> merged = readRow(keepAliveConn, mergedId);
        assertEquals("analyst01", merged.get("createdby"));
        assertEquals("analyst01", merged.get("modifiedby"));
    }

    @Test
    public void mergeRecords_setsGroupIdOnSourcesAndMerged() throws Exception {
        insertSource("M007", "enriched", "500", "10", "510");
        insertSource("M008", "enriched", "300", "5", "305");

        Map<String, Object> result = service.mergeRecords(TABLE,
                Arrays.asList("M007", "M008"), null, config);

        String mergedId = (String) result.get("mergedId");
        Map<String, String> merged = readRow(keepAliveConn, mergedId);
        String groupId = merged.get("c_group_id");
        assertNotNull(groupId);
        UUID.fromString(groupId); // validate UUID

        // Sources also get the group_id
        assertEquals(groupId, readRow(keepAliveConn, "M007").get("c_group_id"));
        assertEquals(groupId, readRow(keepAliveConn, "M008").get("c_group_id"));
    }

    @Test
    public void mergeRecords_writesAuditEntries() throws Exception {
        insertSource("M009", "enriched", "500", "10", "510");
        insertSource("M010", "enriched", "300", "5", "305");

        Map<String, Object> result = service.mergeRecords(TABLE,
                Arrays.asList("M009", "M010"), null, config);

        String mergedId = (String) result.get("mergedId");

        // Merged record audit: null → enriched
        List<Map<String, String>> mergedAudits = readAuditEntries(keepAliveConn, mergedId);
        assertEquals(1, mergedAudits.size());
        assertEquals("null", mergedAudits.get(0).get("c_from_status"));
        assertEquals("enriched", mergedAudits.get(0).get("c_to_status"));
        assertEquals("ENRICHMENT", mergedAudits.get(0).get("c_entity_type"));

        // Source audits: enriched → superseded
        for (String srcId : Arrays.asList("M009", "M010")) {
            List<Map<String, String>> srcAudits = readAuditEntries(keepAliveConn, srcId);
            assertEquals(1, srcAudits.size());
            assertEquals("enriched", srcAudits.get(0).get("c_from_status"));
            assertEquals("superseded", srcAudits.get(0).get("c_to_status"));
        }
    }

    @Test
    public void mergeRecords_unanimousFieldsPreserved() throws Exception {
        // Both have same customer_code
        insertSource("M011", "enriched", "500", "10", "510");
        insertSource("M012", "enriched", "300", "5", "305");

        Map<String, Object> result = service.mergeRecords(TABLE,
                Arrays.asList("M011", "M012"), null, config);

        String mergedId = (String) result.get("mergedId");
        Map<String, String> merged = readRow(keepAliveConn, mergedId);
        assertEquals("CUST-1", merged.get("c_customer_code"));
        assertEquals("D", merged.get("c_debit_credit"));
        assertEquals("FX", merged.get("c_internal_type"));
    }

    @Test
    public void mergeRecords_nonUnanimousFieldsBlank() throws Exception {
        insertSource("M013", "enriched", "500", "10", "510");
        // Second source has different description
        insertEnrichmentRow(keepAliveConn, "M014", fields(
                "status", "enriched",
                "original_amount", "300",
                "fee_amount", "5",
                "total_amount", "305",
                "base_amount_eur", "305",
                "fx_rate_to_eur", "1.0",
                "customer_code", "CUST-1",
                "statement_id", "STMT-1",
                "validated_currency", "EUR",
                "debit_credit", "D",
                "internal_type", "FX",
                "transaction_date", "2025-01-15",
                "description", "Different description"));

        Map<String, Object> result = service.mergeRecords(TABLE,
                Arrays.asList("M013", "M014"), null, config);

        String mergedId = (String) result.get("mergedId");
        Map<String, String> merged = readRow(keepAliveConn, mergedId);
        // description differs → blank
        assertEquals("", merged.get("c_description"));
        // customer_code unanimous → preserved
        assertEquals("CUST-1", merged.get("c_customer_code"));
    }

    @Test
    public void mergeRecords_mergedFieldsOverride() throws Exception {
        insertSource("M015", "enriched", "500", "10", "510");
        insertEnrichmentRow(keepAliveConn, "M016", fields(
                "status", "enriched",
                "original_amount", "300",
                "fee_amount", "5",
                "total_amount", "305",
                "base_amount_eur", "305",
                "fx_rate_to_eur", "1.0",
                "customer_code", "CUST-1",
                "statement_id", "STMT-1",
                "validated_currency", "EUR",
                "debit_credit", "D",
                "internal_type", "FX",
                "transaction_date", "2025-01-15",
                "description", "Different"));

        Map<String, String> overrides = fields("description", "Merged note");
        Map<String, Object> result = service.mergeRecords(TABLE,
                Arrays.asList("M015", "M016"), overrides, config);

        String mergedId = (String) result.get("mergedId");
        Map<String, String> merged = readRow(keepAliveConn, mergedId);
        assertEquals("Merged note", merged.get("c_description"));
    }

    @Test
    public void mergeRecords_differentStatements_throws() throws Exception {
        insertSource("M017", "enriched", "500", "10", "510");
        insertEnrichmentRow(keepAliveConn, "M018", fields(
                "status", "enriched",
                "original_amount", "300",
                "fee_amount", "5",
                "total_amount", "305",
                "base_amount_eur", "305",
                "fx_rate_to_eur", "1.0",
                "customer_code", "CUST-1",
                "statement_id", "STMT-2",
                "validated_currency", "EUR",
                "debit_credit", "D",
                "internal_type", "FX"));

        assertThrows(IllegalArgumentException.class,
                () -> service.mergeRecords(TABLE,
                        Arrays.asList("M017", "M018"), null, config));
    }

    @Test
    public void mergeRecords_differentCurrencies_throws() throws Exception {
        insertSource("M019", "enriched", "500", "10", "510");
        insertEnrichmentRow(keepAliveConn, "M020", fields(
                "status", "enriched",
                "original_amount", "300",
                "fee_amount", "5",
                "total_amount", "305",
                "base_amount_eur", "305",
                "fx_rate_to_eur", "1.0",
                "customer_code", "CUST-1",
                "statement_id", "STMT-1",
                "validated_currency", "USD",
                "debit_credit", "D",
                "internal_type", "FX"));

        assertThrows(IllegalArgumentException.class,
                () -> service.mergeRecords(TABLE,
                        Arrays.asList("M019", "M020"), null, config));
    }

    @Test
    public void mergeRecords_confirmedStatus_throws() throws Exception {
        insertSource("M021", "confirmed", "500", "10", "510");
        insertSource("M022", "enriched", "300", "5", "305");

        assertThrows(IllegalArgumentException.class,
                () -> service.mergeRecords(TABLE,
                        Arrays.asList("M021", "M022"), null, config));
    }

    @Test
    public void mergeRecords_readyStatus_notMergeable() throws Exception {
        insertSource("M023", "ready", "500", "10", "510");
        insertSource("M024", "enriched", "300", "5", "305");

        // READY is not in MERGEABLE_STATUSES (enriched, adjusted, in_review)
        assertThrows(IllegalArgumentException.class,
                () -> service.mergeRecords(TABLE,
                        Arrays.asList("M023", "M024"), null, config));
    }
}
