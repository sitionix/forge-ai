package com.sitionix.forgeagent.api.dto;
import com.sitionix.forgeagent.domain.model.LogProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
public record AssetMonitoringRequest(@NotBlank String name, @NotNull LogProviderType provider,
    String target, boolean enabled) {}
