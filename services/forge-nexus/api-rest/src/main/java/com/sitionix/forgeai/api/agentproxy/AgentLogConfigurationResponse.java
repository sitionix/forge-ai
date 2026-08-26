package com.sitionix.forgeai.api.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentSystemdTargetMode;

public record AgentLogConfigurationResponse(
    String container, String composeService, String composeFile, AgentSystemdTargetMode systemdMode,
    String unit, String path) {
  public AgentLogConfigurationResponse(
      String container, String composeService, String composeFile, String unit, String path) {
    this(container, composeService, composeFile, null, unit, path);
  }
}
