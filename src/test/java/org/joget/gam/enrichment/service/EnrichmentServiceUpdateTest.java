package org.joget.gam.enrichment.service;

import com.fiscaladmin.gam.framework.status.InvalidTransitionException;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.joget.gam.enrichment.service.EnrichmentTestDataFactory.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class EnrichmentServiceUpdateTest {

    private FormDataDao mockDao;
    private EnrichmentService service;

    @Before
    public void setUp() {
        mockDao = mock(FormDataDao.class);
        service = new EnrichmentService();
        service.setDao(mockDao);
    }

    // ── Version locking ──────────────────────────────────────────────

    @Test
    public void versionMatch_succeeds() throws Exception {
        FormRow initial = row("E001", "in_review", 3, Map.of("description", "Old"));
        FormRow reloaded = row("E001", "in_review", 4, Map.of("description", "New"));
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(initial).thenReturn(reloaded);

        FormRow result = service.updateRecord(TABLE, "E001",
                fields("description", "New"), 3, null);
        assertEquals(reloaded, result);
    }

    @Test
    public void versionMismatch_throws() {
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(row("E001", "in_review", 3));

        VersionConflictException e = assertThrows(VersionConflictException.class,
                () -> service.updateRecord(TABLE, "E001",
                        fields("description", "X"), 2, null));
        assertEquals(3, e.getCurrentVersion());
        assertEquals(2, e.getRequestedVersion());
    }

    @Test
    public void nullVersion_treatedAsZero() throws Exception {
        FormRow initial = new FormRow();
        initial.setId("E001");
        initial.setProperty("status", "in_review");
        // no version property set → parseVersion returns 0
        FormRow reloaded = row("E001", "in_review", 1);
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(initial).thenReturn(reloaded);

        FormRow result = service.updateRecord(TABLE, "E001",
                fields("description", "X"), 0, null);
        assertNotNull(result);
    }

    @Test
    public void nonNumericVersion_treatedAsZero() throws Exception {
        FormRow initial = row("E001", "in_review", 0);
        initial.setProperty("version", "abc");
        FormRow reloaded = row("E001", "in_review", 1);
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(initial).thenReturn(reloaded);

        FormRow result = service.updateRecord(TABLE, "E001",
                fields("description", "X"), 0, null);
        assertNotNull(result);
    }

    // ── Not found ────────────────────────────────────────────────────

    @Test
    public void notFound_throwsRecordNotFound() {
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(null);

        assertThrows(RecordNotFoundException.class,
                () -> service.updateRecord(TABLE, "E001",
                        fields("description", "X"), 0, null));
    }

    // ── Terminal statuses ────────────────────────────────────────────

    @Test
    public void confirmed_onlyProcessingNotes() throws Exception {
        FormRow initial = row("E001", "confirmed", 0,
                Map.of("processing_notes", "old", "description", "old desc"));
        FormRow reloaded = row("E001", "confirmed", 1);
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(initial).thenReturn(reloaded);

        service.updateRecord(TABLE, "E001",
                fields("processing_notes", "new note", "description", "new desc"), 0, null);

        // processing_notes applied, description not touched
        assertEquals("new note", initial.getProperty("processing_notes"));
        assertEquals("old desc", initial.getProperty("description"));
    }

    @Test
    public void confirmed_noEditableFields_throws() {
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(row("E001", "confirmed", 0));

        assertThrows(TerminalStatusException.class,
                () -> service.updateRecord(TABLE, "E001",
                        fields("description", "X"), 0, null));
    }

    @Test
    public void superseded_onlyProcessingNotes() throws Exception {
        FormRow initial = row("E001", "superseded", 0,
                Map.of("processing_notes", "old"));
        FormRow reloaded = row("E001", "superseded", 1);
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(initial).thenReturn(reloaded);

        service.updateRecord(TABLE, "E001",
                fields("processing_notes", "updated"), 0, null);

        assertEquals("updated", initial.getProperty("processing_notes"));
    }

    // ── Restricted statuses ──────────────────────────────────────────

    @Test
    public void processing_onlyProcessingNotes() throws Exception {
        FormRow initial = row("E001", "processing", 0,
                Map.of("processing_notes", "old", "description", "old desc"));
        FormRow reloaded = row("E001", "processing", 1);
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(initial).thenReturn(reloaded);

        service.updateRecord(TABLE, "E001",
                fields("processing_notes", "new note", "description", "new desc"), 0, null);

        assertEquals("new note", initial.getProperty("processing_notes"));
        assertEquals("old desc", initial.getProperty("description"));
    }

    @Test
    public void error_noEditableFields_throws() {
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(row("E001", "error", 0));

        assertThrows(TerminalStatusException.class,
                () -> service.updateRecord(TABLE, "E001",
                        fields("description", "X"), 0, null));
    }

    // ── Normal status editability ────────────────────────────────────

    @Test
    public void normalStatus_nonEditableDropped() throws Exception {
        FormRow initial = row("E001", "in_review", 0,
                Map.of("description", "old", "statement_id", "STMT001"));
        FormRow reloaded = row("E001", "in_review", 1);
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(initial).thenReturn(reloaded);

        service.updateRecord(TABLE, "E001",
                fields("description", "New", "statement_id", "STMT999"), 0, null);

        // description (editable) changed, statement_id (non-editable) unchanged
        assertEquals("New", initial.getProperty("description"));
        assertEquals("STMT001", initial.getProperty("statement_id"));
    }

    // ── Auto-transition (ENRICHED → ADJUSTED) ────────────────────────

    @Test
    public void enriched_fieldChange_autoTransitions() throws Exception {
        FormRow initial = row("E001", "enriched", 0, Map.of("description", "Old"));
        FormRow afterSave = row("E001", "enriched", 1, Map.of("description", "New"));
        FormRow finalRow = row("E001", "adjusted", 1, Map.of("description", "New"));

        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(initial)    // 1: updateRecord load
                .thenReturn(afterSave)  // 2: StatusManager.transition load
                .thenReturn(finalRow);  // 3: updateRecord reload

        FormRow result = service.updateRecord(TABLE, "E001",
                fields("description", "New"), 0, null);

        assertEquals("adjusted", result.getProperty("status"));
        // 3 loads confirm StatusManager was invoked
        verify(mockDao, times(3)).load(isNull(), eq(TABLE), eq("E001"));
    }

    @Test
    public void enriched_noActualChange_noTransition() throws Exception {
        FormRow initial = row("E001", "enriched", 0, Map.of("description", "Same"));
        FormRow reloaded = row("E001", "enriched", 1, Map.of("description", "Same"));

        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(initial).thenReturn(reloaded);

        FormRow result = service.updateRecord(TABLE, "E001",
                fields("description", "Same"), 0, null);

        assertEquals("enriched", result.getProperty("status"));
        // Only 2 loads — no StatusManager
        verify(mockDao, times(2)).load(isNull(), eq(TABLE), eq("E001"));
    }

    // ── Confidence overrides ─────────────────────────────────────────

    @Test
    public void override_setsFields() throws Exception {
        FormRow initial = row("E001", "in_review", 0,
                Map.of("internal_type", "OLD"));
        FormRow reloaded = row("E001", "in_review", 1);
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(initial).thenReturn(reloaded);

        JSONArray overrides = confidenceOverrides("internal_type",
                Map.of("confidence_score", "100"));

        service.updateRecord(TABLE, "E001",
                fields("internal_type", "NEW"), 0, overrides);

        assertEquals("100", initial.getProperty("confidence_score"));
    }

    @Test
    public void override_clearsFields() throws Exception {
        FormRow initial = row("E001", "in_review", 0,
                Map.of("internal_type", "OLD", "old_confidence", "50"));
        FormRow reloaded = row("E001", "in_review", 1);
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(initial).thenReturn(reloaded);

        JSONArray overrides = confidenceOverrides("internal_type",
                null, "old_confidence");

        service.updateRecord(TABLE, "E001",
                fields("internal_type", "NEW"), 0, overrides);

        assertEquals("", initial.getProperty("old_confidence"));
    }

    @Test
    public void override_skippedForTerminal() throws Exception {
        FormRow initial = row("E001", "confirmed", 0,
                Map.of("processing_notes", "old", "some_field", "value"));
        FormRow reloaded = row("E001", "confirmed", 1);
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(initial).thenReturn(reloaded);

        JSONArray overrides = confidenceOverrides("processing_notes",
                Map.of("confidence_score", "100"));

        service.updateRecord(TABLE, "E001",
                fields("processing_notes", "new note"), 0, overrides);

        // Override skipped — confidence_score not set
        assertNull(initial.getProperty("confidence_score"));
        assertEquals("value", initial.getProperty("some_field"));
    }

    // ── Combined flow ────────────────────────────────────────────────

    @Test
    public void fullFlow_enrichedToAdjusted_withOverrides() throws Exception {
        FormRow initial = row("E001", "enriched", 0,
                Map.of("description", "Old", "internal_type", "OLD_TYPE",
                        "old_confidence", "50"));
        FormRow afterSave = row("E001", "enriched", 1);
        FormRow finalRow = row("E001", "adjusted", 1);

        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(initial)
                .thenReturn(afterSave)
                .thenReturn(finalRow);

        JSONArray overrides = confidenceOverrides("internal_type",
                Map.of("confidence_score", "100"), "old_confidence");

        FormRow result = service.updateRecord(TABLE, "E001",
                fields("description", "New", "internal_type", "NEW_TYPE",
                        "statement_id", "STMT"),
                0, overrides);

        // Fields applied
        assertEquals("New", initial.getProperty("description"));
        assertEquals("NEW_TYPE", initial.getProperty("internal_type"));
        // Non-editable dropped
        // (statement_id was removed by retainAll before applying)

        // Overrides applied
        assertEquals("100", initial.getProperty("confidence_score"));
        assertEquals("", initial.getProperty("old_confidence"));

        // Auto-transition fired (3 loads)
        verify(mockDao, times(3)).load(isNull(), eq(TABLE), eq("E001"));
        assertEquals("adjusted", result.getProperty("status"));
    }

    // ── WS-2 new editable fields ────────────────────────────────────

    @Test
    public void newWorkspaceFields_accepted() throws Exception {
        FormRow initial = row("E001", "in_review", 0, Map.of(
                "loan_id", "", "gl_debit_override", ""));
        FormRow reloaded = row("E001", "in_review", 1);
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(initial).thenReturn(reloaded);

        service.updateRecord(TABLE, "E001",
                fields("loan_id", "LOAN-001",
                       "loan_direction", "LENDER",
                       "gl_debit_override", "4000",
                       "gl_credit_override", "5000",
                       "gl_override_reason", "Manual GL mapping",
                       "pair_id", "PAIR-123",
                       "base_amount_eur", "1500.00"),
                0, null);

        assertEquals("LOAN-001", initial.getProperty("loan_id"));
        assertEquals("LENDER", initial.getProperty("loan_direction"));
        assertEquals("4000", initial.getProperty("gl_debit_override"));
        assertEquals("5000", initial.getProperty("gl_credit_override"));
        assertEquals("Manual GL mapping", initial.getProperty("gl_override_reason"));
        assertEquals("PAIR-123", initial.getProperty("pair_id"));
        assertEquals("1500.00", initial.getProperty("base_amount_eur"));
    }
}
