package com.sitionix.forgeagent.domain.model;

public record SystemdLogConfiguration(SystemdTargetMode mode, String unit)
    implements LogProviderConfiguration {
  public SystemdLogConfiguration(String unit) {
    this(SystemdTargetMode.UNIT, unit);
  }
}
