package org.joget.gam.enrichment.service;

import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.junit.Before;
import org.junit.Test;

import static org.joget.gam.enrichment.service.EnrichmentTestDataFactory.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class EnrichmentServiceDeleteTest {

    private FormDataDao mockDao;
    private EnrichmentService service;

    @Before
    public void setUp() {
        mockDao = mock(FormDataDao.class);
        service = new EnrichmentService();
        service.setDao(mockDao);
    }

    // ── Happy path: deletable statuses ────────────────────────────────

    @Test
    public void newStatus_succeeds() throws Exception {
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(row("E001", "new", 0));

        service.deleteRecord(TABLE, "E001");

        verify(mockDao).delete(isNull(), eq(TABLE), eq(new String[]{"E001"}));
    }

    @Test
    public void errorStatus_succeeds() throws Exception {
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(row("E001", "error", 0));

        service.deleteRecord(TABLE, "E001");

        verify(mockDao).delete(isNull(), eq(TABLE), eq(new String[]{"E001"}));
    }

    @Test
    public void manualReviewStatus_succeeds() throws Exception {
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(row("E001", "manual_review", 0));

        service.deleteRecord(TABLE, "E001");

        verify(mockDao).delete(isNull(), eq(TABLE), eq(new String[]{"E001"}));
    }

    // ── Rejected: non-deletable statuses ──────────────────────────────

    @Test
    public void enrichedStatus_throwsDeleteNotAllowed() {
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(row("E001", "enriched", 0));

        DeleteNotAllowedException e = assertThrows(DeleteNotAllowedException.class,
                () -> service.deleteRecord(TABLE, "E001"));
        assertEquals("E001", e.getRecordId());
        assertEquals("enriched", e.getCurrentStatus());

        verify(mockDao, never()).delete(any(), anyString(), any(String[].class));
    }

    @Test
    public void confirmedStatus_throws() {
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(row("E001", "confirmed", 0));

        assertThrows(DeleteNotAllowedException.class,
                () -> service.deleteRecord(TABLE, "E001"));

        verify(mockDao, never()).delete(any(), anyString(), any(String[].class));
    }

    @Test
    public void readyStatus_throws() {
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(row("E001", "ready", 0));

        assertThrows(DeleteNotAllowedException.class,
                () -> service.deleteRecord(TABLE, "E001"));

        verify(mockDao, never()).delete(any(), anyString(), any(String[].class));
    }

    // ── Not found ─────────────────────────────────────────────────────

    @Test
    public void notFound_throwsRecordNotFound() {
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(null);

        RecordNotFoundException e = assertThrows(RecordNotFoundException.class,
                () -> service.deleteRecord(TABLE, "E001"));
        assertEquals("E001", e.getRecordId());
    }
}
