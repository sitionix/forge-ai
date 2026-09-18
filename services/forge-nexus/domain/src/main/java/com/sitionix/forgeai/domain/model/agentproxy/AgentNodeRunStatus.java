package com.sitionix.forgeai.domain.model.agentproxy;

public enum AgentNodeRunStatus {
    PENDING,
    RUNNING,
    WAITING_FOR_MANUAL,
    SUCCEEDED,
    FAILED,
    BLOCKED,
    CANCELLED
}
