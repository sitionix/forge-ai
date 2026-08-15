package com.sitionix.forgeagent.domain.model;

import java.util.UUID;

public record RunConnection(
        UUID workflowRunId,
        UUID sourceConnectionId,
        UUID sourceOutputPortId,
        UUID targetInputPortId
) {
}
