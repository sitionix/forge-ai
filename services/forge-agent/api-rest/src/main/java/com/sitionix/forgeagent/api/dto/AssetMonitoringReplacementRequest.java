package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.LogProviderType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record AssetMonitoringReplacementRequest(@NotNull List<@Valid Target> targets) {
  public record Target(@NotNull LogProviderType provider, @NotBlank String target) {}
}
