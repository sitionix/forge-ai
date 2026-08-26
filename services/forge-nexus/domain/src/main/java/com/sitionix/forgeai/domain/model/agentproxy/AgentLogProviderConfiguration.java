package com.sitionix.forgeai.domain.model.agentproxy;

public sealed interface AgentLogProviderConfiguration
    permits AgentDockerLogConfiguration, AgentSystemdLogConfiguration, AgentFileLogConfiguration {}
