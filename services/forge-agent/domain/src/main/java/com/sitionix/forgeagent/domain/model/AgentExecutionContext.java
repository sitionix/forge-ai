package com.sitionix.forgeagent.domain.model;

/** Inspection only: a durable fork successor can exist before its first real invocation. */
public record AgentExecutionContext(AgentExecutionSession session, AgentExecutionTurn turn, boolean workflowTerminal) { }
