package org.joget.gam.enrichment.service;

public class VersionConflictException extends Exception {

    private final String recordId;
    private final int currentVersion;
    private final int requestedVersion;

    public VersionConflictException(String recordId, int currentVersion, int requestedVersion) {
        super("Version conflict for record " + recordId
                + ": current=" + currentVersion + ", requested=" + requestedVersion);
        this.recordId = recordId;
        this.currentVersion = currentVersion;
        this.requestedVersion = requestedVersion;
    }

    public String getRecordId() {
        return recordId;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    public int getRequestedVersion() {
        return requestedVersion;
    }
}
