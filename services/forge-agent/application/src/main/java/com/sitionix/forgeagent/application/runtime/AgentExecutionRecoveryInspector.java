package com.sitionix.forgeagent.application.runtime;

public interface AgentExecutionRecoveryInspector {

    boolean supports(String providerId, String providerVersion);

    ProviderTurnRecoveryResult inspect(AgentExecutionRecoveryInspection inspection);
}
