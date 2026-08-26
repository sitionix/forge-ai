package com.sitionix.forgeai.domain.model.agentproxy;

public record AgentSystemdLogConfiguration(AgentSystemdTargetMode mode, String unit)
        implements AgentLogProviderConfiguration {
    public AgentSystemdLogConfiguration(final String unit) {
        this(AgentSystemdTargetMode.UNIT, unit);
    }
}
