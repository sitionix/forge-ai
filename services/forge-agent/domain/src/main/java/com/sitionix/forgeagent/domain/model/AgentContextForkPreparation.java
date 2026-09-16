package com.sitionix.forgeagent.domain.model;

/** Exact persisted boundary and fencing token captured by fork preparation. */
public record AgentContextForkPreparation(AgentExecutionSession session, AgentExecutionTurn turn) { }
