package com.sitionix.forgeai.domain.model.agentproxy;

public enum AgentNodeRunStatus {
    PENDING,
    RUNNING,
    WAITING_FOR_MANUAL,
    WAITING_FOR_DIALOGUE,
    SUCCEEDED,
    FAILED,
    BLOCKED,
    CANCELLED
}
