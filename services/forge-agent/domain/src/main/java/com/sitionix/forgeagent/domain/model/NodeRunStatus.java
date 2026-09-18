package com.sitionix.forgeagent.domain.model;

public enum NodeRunStatus {
    PENDING,
    RUNNING,
    WAITING_FOR_MANUAL,
    SUCCEEDED,
    FAILED,
    BLOCKED,
    CANCELLED;

    public boolean active() {
        return this == PENDING || this == RUNNING || this == WAITING_FOR_MANUAL;
    }
}
