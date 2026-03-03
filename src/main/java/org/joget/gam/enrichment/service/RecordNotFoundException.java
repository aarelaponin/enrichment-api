package org.joget.gam.enrichment.service;

public class RecordNotFoundException extends Exception {

    private final String recordId;

    public RecordNotFoundException(String recordId) {
        super("Record not found: " + recordId);
        this.recordId = recordId;
    }

    public String getRecordId() {
        return recordId;
    }
}
