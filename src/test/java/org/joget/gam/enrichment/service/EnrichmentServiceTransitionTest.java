package org.joget.gam.enrichment.service;

import com.fiscaladmin.gam.framework.status.InvalidTransitionException;
import com.fiscaladmin.gam.framework.status.Status;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.joget.gam.enrichment.service.EnrichmentTestDataFactory.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
public class EnrichmentServiceTransitionTest {

    private FormDataDao mockDao;
    private EnrichmentService service;

    @Before
    public void setUp() {
        mockDao = mock(FormDataDao.class);
        service = new EnrichmentService();
        service.setDao(mockDao);
    }

    // ── Single transition ────────────────────────────────────────────

    @Test
    public void validTransition_returnsResult() throws Exception {
        // ENRICHED → ADJUSTED is valid
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(row("E001", "enriched", 0))  // transitionStatus load
                .thenReturn(row("E001", "enriched", 0))  // StatusManager load
                .thenReturn(row("E001", "adjusted", 1)); // transitionStatus reload

        Map<String, Object> result = service.transitionStatus(TABLE, "E001",
                Status.ADJUSTED, "test reason");

        assertEquals("E001", result.get("id"));
        assertEquals("enriched", result.get("previousStatus"));
        assertEquals("adjusted", result.get("newStatus"));
    }

    @Test
    public void notFound_throws() {
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(null);

        assertThrows(RecordNotFoundException.class,
                () -> service.transitionStatus(TABLE, "E001", Status.ADJUSTED, null));
    }

    @Test
    public void invalidTransition_throws() {
        // CONFIRMED is terminal — cannot transition to READY
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(row("E001", "confirmed", 0))  // transitionStatus load
                .thenReturn(row("E001", "confirmed", 0)); // StatusManager load

        assertThrows(InvalidTransitionException.class,
                () -> service.transitionStatus(TABLE, "E001", Status.READY, null));
    }

    @Test
    public void statusManagerNotFound_throws() {
        // First load succeeds (EnrichmentService), second returns null (StatusManager)
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(row("E001", "enriched", 0))
                .thenReturn(null);

        // StatusManager throws IllegalStateException → caught as RecordNotFoundException
        assertThrows(RecordNotFoundException.class,
                () -> service.transitionStatus(TABLE, "E001", Status.ADJUSTED, null));
    }

    // ── Batch transition ─────────────────────────────────────────────

    @Test
    public void batch_allSucceed() {
        // E001: ENRICHED → ADJUSTED
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(row("E001", "enriched", 0))
                .thenReturn(row("E001", "enriched", 0))
                .thenReturn(row("E001", "adjusted", 1));
        // E002: ENRICHED → ADJUSTED
        when(mockDao.load(isNull(), eq(TABLE), eq("E002")))
                .thenReturn(row("E002", "enriched", 0))
                .thenReturn(row("E002", "enriched", 0))
                .thenReturn(row("E002", "adjusted", 1));

        Map<String, Object> result = service.batchTransitionStatus(TABLE,
                List.of("E001", "E002"), Status.ADJUSTED, "batch test");

        List<Map<String, Object>> succeeded = (List<Map<String, Object>>) result.get("succeeded");
        List<Map<String, Object>> failed = (List<Map<String, Object>>) result.get("failed");
        assertEquals(2, succeeded.size());
        assertEquals(0, failed.size());
    }

    @Test
    public void batch_allFail() {
        when(mockDao.load(isNull(), eq(TABLE), eq("E001"))).thenReturn(null);
        when(mockDao.load(isNull(), eq(TABLE), eq("E002"))).thenReturn(null);

        Map<String, Object> result = service.batchTransitionStatus(TABLE,
                List.of("E001", "E002"), Status.ADJUSTED, "batch test");

        List<Map<String, Object>> succeeded = (List<Map<String, Object>>) result.get("succeeded");
        List<Map<String, Object>> failed = (List<Map<String, Object>>) result.get("failed");
        assertEquals(0, succeeded.size());
        assertEquals(2, failed.size());
        assertEquals("Record not found", failed.get(0).get("error"));
    }

    @Test
    public void batch_mixedResults() {
        // E001 succeeds
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(row("E001", "enriched", 0))
                .thenReturn(row("E001", "enriched", 0))
                .thenReturn(row("E001", "adjusted", 1));
        // E002 not found
        when(mockDao.load(isNull(), eq(TABLE), eq("E002"))).thenReturn(null);

        Map<String, Object> result = service.batchTransitionStatus(TABLE,
                List.of("E001", "E002"), Status.ADJUSTED, "test");

        assertEquals(1, ((List<?>) result.get("succeeded")).size());
        assertEquals(1, ((List<?>) result.get("failed")).size());
    }

    @Test
    public void batch_emptyList() {
        Map<String, Object> result = service.batchTransitionStatus(TABLE,
                List.of(), Status.ADJUSTED, "test");

        assertTrue(((List<?>) result.get("succeeded")).isEmpty());
        assertTrue(((List<?>) result.get("failed")).isEmpty());
    }

    @Test
    public void batch_failedContainsCurrentStatus() {
        // CONFIRMED → READY is invalid (terminal status)
        when(mockDao.load(isNull(), eq(TABLE), eq("E001")))
                .thenReturn(row("E001", "confirmed", 0))
                .thenReturn(row("E001", "confirmed", 0));

        Map<String, Object> result = service.batchTransitionStatus(TABLE,
                List.of("E001"), Status.READY, "test");

        List<Map<String, Object>> failed = (List<Map<String, Object>>) result.get("failed");
        assertEquals(1, failed.size());
        assertEquals("E001", failed.get(0).get("id"));
        assertEquals("confirmed", failed.get(0).get("currentStatus"));
    }
}
