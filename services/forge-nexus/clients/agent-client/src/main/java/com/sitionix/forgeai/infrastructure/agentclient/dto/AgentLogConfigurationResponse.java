package com.sitionix.forgeai.infrastructure.agentclient.dto;

public record AgentLogConfigurationResponse(
    String container, String composeService, String composeFile, String unit, String path) {}
