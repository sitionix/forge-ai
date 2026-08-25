package com.sitionix.forgeai.api.agentproxy;

public record AgentLogConfigurationResponse(
    String container, String composeService, String composeFile, String unit, String path) {}
