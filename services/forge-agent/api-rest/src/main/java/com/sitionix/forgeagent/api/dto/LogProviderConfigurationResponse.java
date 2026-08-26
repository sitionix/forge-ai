package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.SystemdTargetMode;

public record LogProviderConfigurationResponse(
    String container, String composeService, String composeFile, SystemdTargetMode systemdMode,
    String unit, String path) {
  public LogProviderConfigurationResponse(
      String container, String composeService, String composeFile, String unit, String path) {
    this(container, composeService, composeFile, null, unit, path);
  }
}
