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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.joget.gam.enrichment.service.JdbcTestHelper.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for EnrichmentService.splitRecord() — uses MockedStatic + H2.
 */
public class EnrichmentServiceSplitTest {

    private Connection keepAliveConn;
    private MockedStatic<AppUtil> mockedAppUtil;
    private EnrichmentService service;
    private ValidationConfig config;
    private String dbUrl;

    @Before
    public void setUp() throws Exception {
        String dbName = "split_" + UUID.randomUUID().toString().replace("-", "");
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

    private void insertParent(String id, String status, String amount,
                               String fee, String total) throws SQLException {
        insertEnrichmentRow(keepAliveConn, id, fields(
                "status", status,
                "original_amount", amount,
                "fee_amount", fee,
                "total_amount", total,
                "base_amount_eur", total,
                "fx_rate_to_eur", "1.0",
                "customer_code", "CUST-PARENT",
                "statement_id", "STMT-1",
                "validated_currency", "EUR",
                "debit_credit", "D",
                "internal_type", "FX",
                "transaction_date", "2025-01-15",
                "description", "Parent transaction"));
    }

    private List<Map<String, String>> twoAllocations(String amt1, String fee1,
                                                       String amt2, String fee2) {
        List<Map<String, String>> allocs = new ArrayList<>();
        allocs.add(fields("original_amount", amt1, "fee_amount", fee1, "customer_code", "CUST-A"));
        allocs.add(fields("original_amount", amt2, "fee_amount", fee2, "customer_code", "CUST-B"));
        return allocs;
    }

    @Test
    public void splitRecord_createsChildrenAndSupersedesParent() throws Exception {
        insertParent("P001", "enriched", "1000", "10", "1010");

        Map<String, Object> result = service.splitRecord(TABLE, "P001",
                twoAllocations("600", "6", "400", "4"), config);

        assertEquals("P001", result.get("parentId"));
        assertEquals("superseded", result.get("parentStatus"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) result.get("children");
        assertEquals(2, children.size());

        // Parent superseded in DB
        Map<String, String> parent = readRow(keepAliveConn, "P001");
        assertEquals("superseded", parent.get("c_status"));

        // Children created
        assertNotNull(readRow(keepAliveConn, "P001-S1"));
        assertNotNull(readRow(keepAliveConn, "P001-S2"));
    }

    @Test
    public void splitRecord_usesUuidGroupId() throws Exception {
        insertParent("P002", "enriched", "1000", "10", "1010");

        service.splitRecord(TABLE, "P002",
                twoAllocations("600", "6", "400", "4"), config);

        Map<String, String> child1 = readRow(keepAliveConn, "P002-S1");
        Map<String, String> child2 = readRow(keepAliveConn, "P002-S2");

        String groupId1 = child1.get("c_group_id");
        String groupId2 = child2.get("c_group_id");

        assertNotNull(groupId1);
        assertEquals(groupId1, groupId2);
        // Must be a UUID, not the parent ID
        assertNotEquals("P002", groupId1);
        // Validate UUID format
        UUID.fromString(groupId1); // throws if not valid UUID
    }

    @Test
    public void splitRecord_setsCreatedByOnChildren() throws Exception {
        insertParent("P003", "enriched", "1000", "10", "1010");

        service.splitRecord(TABLE, "P003",
                twoAllocations("600", "6", "400", "4"), config);

        Map<String, String> child1 = readRow(keepAliveConn, "P003-S1");
        assertEquals("analyst01", child1.get("createdby"));
        assertEquals("analyst01", child1.get("modifiedby"));
    }

    @Test
    public void splitRecord_writesAuditEntries() throws Exception {
        insertParent("P004", "enriched", "1000", "10", "1010");

        service.splitRecord(TABLE, "P004",
                twoAllocations("600", "6", "400", "4"), config);

        // Parent audit: enriched → superseded
        List<Map<String, String>> parentAudits = readAuditEntries(keepAliveConn, "P004");
        assertEquals(1, parentAudits.size());
        assertEquals("enriched", parentAudits.get(0).get("c_from_status"));
        assertEquals("superseded", parentAudits.get(0).get("c_to_status"));
        assertEquals("ENRICHMENT", parentAudits.get(0).get("c_entity_type"));

        // Child audits: null → enriched
        for (String childId : Arrays.asList("P004-S1", "P004-S2")) {
            List<Map<String, String>> childAudits = readAuditEntries(keepAliveConn, childId);
            assertEquals(1, childAudits.size());
            assertEquals("null", childAudits.get(0).get("c_from_status"));
            assertEquals("enriched", childAudits.get(0).get("c_to_status"));
        }
    }

    @Test
    public void splitRecord_setsLineageFields() throws Exception {
        insertParent("P005", "enriched", "1000", "10", "1010");

        service.splitRecord(TABLE, "P005",
                twoAllocations("600", "6", "400", "4"), config);

        Map<String, String> child1 = readRow(keepAliveConn, "P005-S1");
        assertEquals("split", child1.get("c_origin"));
        assertEquals("P005", child1.get("c_parent_enrichment_id"));
        assertEquals("1", child1.get("c_split_sequence"));
        assertTrue(child1.get("c_lineage_note").contains("Split from P005"));

        Map<String, String> child2 = readRow(keepAliveConn, "P005-S2");
        assertEquals("2", child2.get("c_split_sequence"));
    }

    @Test
    public void splitRecord_calculatesAmountsCorrectly() throws Exception {
        insertParent("P006", "enriched", "1000", "50", "1050");

        service.splitRecord(TABLE, "P006",
                twoAllocations("600", "30", "400", "20"), config);

        Map<String, String> child1 = readRow(keepAliveConn, "P006-S1");
        assertEquals("600", child1.get("c_original_amount"));
        assertEquals("30", child1.get("c_fee_amount"));
        assertEquals("630", child1.get("c_total_amount"));

        Map<String, String> child2 = readRow(keepAliveConn, "P006-S2");
        assertEquals("400", child2.get("c_original_amount"));
        assertEquals("20", child2.get("c_fee_amount"));
        assertEquals("420", child2.get("c_total_amount"));
    }

    @Test
    public void splitRecord_feeRounding_adjustsLastChild() throws Exception {
        // Parent fee = 10, but allocations sum to 9 (5+4). Remainder = 1 → added to last.
        insertParent("P007", "enriched", "1000", "10", "1010");

        List<Map<String, String>> allocs = twoAllocations("600", "5", "400", "4");
        service.splitRecord(TABLE, "P007", allocs, config);

        Map<String, String> child2 = readRow(keepAliveConn, "P007-S2");
        // 4 + 1 remainder = 5
        assertEquals("5", child2.get("c_fee_amount"));
    }

    @Test
    public void splitRecord_adjustedStatus_allowed() throws Exception {
        insertParent("P008", "adjusted", "1000", "10", "1010");

        Map<String, Object> result = service.splitRecord(TABLE, "P008",
                twoAllocations("600", "6", "400", "4"), config);

        assertEquals("superseded", result.get("parentStatus"));
        Map<String, String> parent = readRow(keepAliveConn, "P008");
        assertEquals("superseded", parent.get("c_status"));
    }

    @Test
    public void splitRecord_inReviewStatus_allowed() throws Exception {
        insertParent("P009", "in_review", "1000", "10", "1010");

        Map<String, Object> result = service.splitRecord(TABLE, "P009",
                twoAllocations("600", "6", "400", "4"), config);

        assertEquals("superseded", result.get("parentStatus"));
    }

    @Test
    public void splitRecord_readyStatus_allowed() throws Exception {
        insertParent("P010", "ready", "1000", "10", "1010");

        Map<String, Object> result = service.splitRecord(TABLE, "P010",
                twoAllocations("600", "6", "400", "4"), config);

        assertEquals("superseded", result.get("parentStatus"));
    }

    @Test
    public void splitRecord_confirmedStatus_throws() throws Exception {
        insertParent("P011", "confirmed", "1000", "10", "1010");

        assertThrows(IllegalArgumentException.class,
                () -> service.splitRecord(TABLE, "P011",
                        twoAllocations("600", "6", "400", "4"), config));
    }

    @Test
    public void splitRecord_notFound_throws() {
        assertThrows(RecordNotFoundException.class,
                () -> service.splitRecord(TABLE, "NONEXISTENT",
                        twoAllocations("600", "6", "400", "4"), config));
    }

    @Test
    public void splitRecord_amountMismatch_throws() throws Exception {
        insertParent("P012", "enriched", "1000", "10", "1010");

        // Amounts sum to 500+400=900, not 1000
        assertThrows(IllegalArgumentException.class,
                () -> service.splitRecord(TABLE, "P012",
                        twoAllocations("500", "5", "400", "5"), config));
    }

    // ── Per-child field overrides (WS-3) ─────────────────────────────

    @Test
    public void splitRecord_perChildInternalType() throws Exception {
        insertParent("P020", "enriched", "1000", "10", "1010");

        List<Map<String, String>> allocs = new ArrayList<>();
        allocs.add(fields("original_amount", "600", "fee_amount", "6",
                "customer_code", "CUST-A", "internal_type", "LOAN_PAYMENT"));
        allocs.add(fields("original_amount", "400", "fee_amount", "4",
                "customer_code", "CUST-B", "internal_type", "INT_INCOME"));

        service.splitRecord(TABLE, "P020", allocs, config);

        Map<String, String> child1 = readRow(keepAliveConn, "P020-S1");
        Map<String, String> child2 = readRow(keepAliveConn, "P020-S2");
        assertEquals("LOAN_PAYMENT", child1.get("c_internal_type"));
        assertEquals("INT_INCOME", child2.get("c_internal_type"));
    }

    @Test
    public void splitRecord_perChildDescription() throws Exception {
        insertParent("P021", "enriched", "1000", "10", "1010");

        List<Map<String, String>> allocs = new ArrayList<>();
        allocs.add(fields("original_amount", "600", "fee_amount", "6",
                "customer_code", "CUST-A", "description", "Principal portion"));
        allocs.add(fields("original_amount", "400", "fee_amount", "4",
                "customer_code", "CUST-B", "description", "Interest portion"));

        service.splitRecord(TABLE, "P021", allocs, config);

        Map<String, String> child1 = readRow(keepAliveConn, "P021-S1");
        Map<String, String> child2 = readRow(keepAliveConn, "P021-S2");
        assertEquals("Principal portion", child1.get("c_description"));
        assertEquals("Interest portion", child2.get("c_description"));
    }

    @Test
    public void splitRecord_perChildLoanFields() throws Exception {
        insertParent("P022", "enriched", "1000", "10", "1010");

        List<Map<String, String>> allocs = new ArrayList<>();
        allocs.add(fields("original_amount", "600", "fee_amount", "6",
                "customer_code", "CUST-A",
                "loan_id", "LOAN-001", "loan_direction", "LENDER"));
        allocs.add(fields("original_amount", "400", "fee_amount", "4",
                "customer_code", "CUST-B",
                "loan_id", "LOAN-002", "loan_direction", "BORROWER"));

        service.splitRecord(TABLE, "P022", allocs, config);

        Map<String, String> child1 = readRow(keepAliveConn, "P022-S1");
        Map<String, String> child2 = readRow(keepAliveConn, "P022-S2");
        assertEquals("LOAN-001", child1.get("c_loan_id"));
        assertEquals("LENDER", child1.get("c_loan_direction"));
        assertEquals("LOAN-002", child2.get("c_loan_id"));
        assertEquals("BORROWER", child2.get("c_loan_direction"));
    }

    @Test
    public void splitRecord_perChildTransactionDate() throws Exception {
        insertParent("P023", "enriched", "1000", "10", "1010");

        List<Map<String, String>> allocs = new ArrayList<>();
        allocs.add(fields("original_amount", "600", "fee_amount", "6",
                "customer_code", "CUST-A", "transaction_date", "2025-01-15"));
        allocs.add(fields("original_amount", "400", "fee_amount", "4",
                "customer_code", "CUST-B", "transaction_date", "2025-02-15"));

        service.splitRecord(TABLE, "P023", allocs, config);

        Map<String, String> child1 = readRow(keepAliveConn, "P023-S1");
        Map<String, String> child2 = readRow(keepAliveConn, "P023-S2");
        assertEquals("2025-01-15", child1.get("c_transaction_date"));
        assertEquals("2025-02-15", child2.get("c_transaction_date"));
    }

    @Test
    public void splitRecord_nonEditableFieldsIgnored() throws Exception {
        insertParent("P024", "enriched", "1000", "10", "1010");

        List<Map<String, String>> allocs = new ArrayList<>();
        allocs.add(fields("original_amount", "600", "fee_amount", "6",
                "customer_code", "CUST-A",
                "statement_id", "HACKED-STMT")); // not in EDITABLE_FIELDS
        allocs.add(fields("original_amount", "400", "fee_amount", "4",
                "customer_code", "CUST-B"));

        service.splitRecord(TABLE, "P024", allocs, config);

        Map<String, String> child1 = readRow(keepAliveConn, "P024-S1");
        // statement_id should be inherited from parent, not the alloc override
        assertEquals("STMT-1", child1.get("c_statement_id"));
    }

    @Test
    public void splitRecord_withoutOverrides_inheritsParentFields() throws Exception {
        // Verify that children without per-child overrides still inherit parent internal_type
        insertParent("P025", "enriched", "1000", "10", "1010");

        service.splitRecord(TABLE, "P025",
                twoAllocations("600", "6", "400", "4"), config);

        Map<String, String> child1 = readRow(keepAliveConn, "P025-S1");
        assertEquals("FX", child1.get("c_internal_type")); // inherited from parent
    }
}
