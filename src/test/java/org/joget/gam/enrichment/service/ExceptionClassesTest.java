package org.joget.gam.enrichment.service;

import com.fiscaladmin.gam.framework.status.Status;
import org.junit.Test;

import static org.junit.Assert.*;

public class ExceptionClassesTest {

    @Test
    public void recordNotFoundException() {
        RecordNotFoundException e = new RecordNotFoundException("E001");
        assertEquals("E001", e.getRecordId());
        assertEquals("Record not found: E001", e.getMessage());
    }

    @Test
    public void versionConflictException() {
        VersionConflictException e = new VersionConflictException("E001", 3, 2);
        assertEquals("E001", e.getRecordId());
        assertEquals(3, e.getCurrentVersion());
        assertEquals(2, e.getRequestedVersion());
        assertTrue(e.getMessage().contains("current=3"));
        assertTrue(e.getMessage().contains("requested=2"));
    }

    @Test
    public void terminalStatusException() {
        TerminalStatusException e = new TerminalStatusException("E001", Status.CONFIRMED);
        assertEquals("E001", e.getRecordId());
        assertEquals(Status.CONFIRMED, e.getStatus());
        assertTrue(e.getMessage().contains("confirmed"));
    }

    @Test
    public void deleteNotAllowedException() {
        DeleteNotAllowedException e = new DeleteNotAllowedException("E001", "enriched");
        assertEquals("E001", e.getRecordId());
        assertEquals("enriched", e.getCurrentStatus());
        assertTrue(e.getMessage().contains("enriched"));
        assertTrue(e.getMessage().contains("new, error, manual_review"));
    }
}
