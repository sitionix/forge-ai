package com.sitionix.forgeagent.domain.model;

public enum AgentExecutionRecoveryDisposition {
    FORGE_TERMINAL,
    PROVIDER_TERMINAL_RESULT_LOST,
    PROVIDER_ACTIVE_FAIL_CLOSED,
    PROVIDER_UNKNOWN_FAIL_CLOSED
}
