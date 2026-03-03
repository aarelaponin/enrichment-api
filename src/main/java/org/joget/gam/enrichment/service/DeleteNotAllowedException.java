package org.joget.gam.enrichment.service;

public class DeleteNotAllowedException extends Exception {

    private final String recordId;
    private final String currentStatus;

    public DeleteNotAllowedException(String recordId, String currentStatus) {
        super("Cannot delete record " + recordId + " with status '" + currentStatus
                + "'. Deletion is only allowed for: new, error, manual_review.");
        this.recordId = recordId;
        this.currentStatus = currentStatus;
    }

    public String getRecordId() {
        return recordId;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }
}
