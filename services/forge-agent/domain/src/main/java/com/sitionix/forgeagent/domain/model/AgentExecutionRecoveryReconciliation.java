package com.sitionix.forgeagent.domain.model;

/** Application-selected disposition and provider evidence; check time is assigned by the database. */
public record AgentExecutionRecoveryReconciliation(
        AgentExecutionRecoveryDisposition disposition,
        ProviderTurnRecoveryTerminalOutcome providerTerminalOutcome,
        String failureCode, String failureMessage) {
}
