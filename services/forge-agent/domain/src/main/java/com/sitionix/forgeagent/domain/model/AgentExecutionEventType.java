package com.sitionix.forgeagent.domain.model;

public enum AgentExecutionEventType {
    TURN,
    PLAN,
    REASONING_SUMMARY,
    COMMAND,
    FILE_CHANGE,
    TOOL_CALL,
    AGENT_MESSAGE,
    WARNING,
    ERROR,
    TOKEN_USAGE,
    CONTEXT_COMPACTION
}
