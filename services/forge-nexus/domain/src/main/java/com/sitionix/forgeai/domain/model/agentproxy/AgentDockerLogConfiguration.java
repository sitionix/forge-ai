package com.sitionix.forgeai.domain.model.agentproxy;

public record AgentDockerLogConfiguration(
    String container, String composeService, String composeFile)
    implements AgentLogProviderConfiguration {}
