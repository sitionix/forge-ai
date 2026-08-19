package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import java.util.List;
import java.util.UUID;

public record InputParticipation(
        UUID workflowRunId,
        UUID activationFrameId,
        UUID targetInputPortId,
        boolean open,
        List<ConnectionResolution> delivered,
        UUID repositoryId
) {
    public InputParticipation(final UUID workflowRunId, final UUID activationFrameId, final UUID targetInputPortId,
                              final boolean open, final List<ConnectionResolution> delivered) {
        this(workflowRunId, activationFrameId, targetInputPortId, open, delivered, null);
    }
}
