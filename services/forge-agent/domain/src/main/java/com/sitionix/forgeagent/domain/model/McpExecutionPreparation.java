package com.sitionix.forgeagent.domain.model;

import java.util.Objects;

/** Separates inspectable selection from launch-only bearer material. */
public record McpExecutionPreparation(McpExecutionSelection selection, McpRuntimeLaunchGrants launchGrants) {
    public McpExecutionPreparation {
        Objects.requireNonNull(selection);
        Objects.requireNonNull(launchGrants);
    }

    @Override public String toString() { return "McpExecutionPreparation[selection=" + selection + ", launchGrants=[REDACTED]]"; }
}
