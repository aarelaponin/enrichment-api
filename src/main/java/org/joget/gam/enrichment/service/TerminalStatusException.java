package org.joget.gam.enrichment.service;

import com.fiscaladmin.gam.framework.status.Status;

public class TerminalStatusException extends Exception {

    private final String recordId;
    private final Status status;

    public TerminalStatusException(String recordId, Status status) {
        super("Record " + recordId + " is in terminal status: " + status.getCode());
        this.recordId = recordId;
        this.status = status;
    }

    public String getRecordId() {
        return recordId;
    }

    public Status getStatus() {
        return status;
    }
}
