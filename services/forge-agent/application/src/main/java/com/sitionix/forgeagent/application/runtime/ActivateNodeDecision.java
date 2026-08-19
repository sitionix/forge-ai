package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import java.util.List;
import java.util.UUID;

public record ActivateNodeDecision(
        UUID workflowRunId,
        UUID activationFrameId,
        UUID targetInputPortId,
        List<ConnectionResolution> delivered,
        UUID repositoryId
) implements ActivationDecision {
    public ActivateNodeDecision(final UUID workflowRunId, final UUID activationFrameId,
                                final UUID targetInputPortId, final List<ConnectionResolution> delivered) {
        this(workflowRunId, activationFrameId, targetInputPortId, delivered, null);
    }

    @Override
    public void apply(final ActivationDecisionHandler handler) {
        handler.handle(this);
    }
}
