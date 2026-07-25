package com.sitionix.forgeai.domain.model.laneexecution;

public enum LaneExecutionStatus {
    STARTING,
    SESSION_STARTED,
    STEP_RUNNING,
    TURN_RUNNING,
    WAITING_FOR_CODEX,
    VALIDATING_RESPONSE,
    PERSISTING_STEP,
    CORRECTION_RUNNING,
    COMPLETING_LANE,
    COMPLETED,
    FAILED,
    CANCEL_REQUESTED,
    INTERRUPTED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED
                || this == FAILED
                || this == INTERRUPTED
                || this == CANCELLED;
    }
}
