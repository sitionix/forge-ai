package com.sitionix.forgeagent.api.dto;

public record LogProviderConfigurationResponse(
    String container, String composeService, String composeFile, String unit, String path) {}
