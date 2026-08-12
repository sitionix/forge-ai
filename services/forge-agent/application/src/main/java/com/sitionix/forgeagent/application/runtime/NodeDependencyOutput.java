package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import java.util.UUID;

public record NodeDependencyOutput(
        UUID nodeRunId,
        String agentName,
        NodeRunOutput output
) {
}
