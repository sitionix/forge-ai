package com.sitionix.forgeagent.application.runtime;

import java.util.UUID;

public record CloseActivationDecision(UUID workflowRunId, UUID activationFrameId, UUID targetInputPortId,
                                      UUID repositoryId) implements ActivationDecision {
    public CloseActivationDecision(final UUID workflowRunId, final UUID activationFrameId, final UUID targetInputPortId) {
        this(workflowRunId, activationFrameId, targetInputPortId, null);
    }

    @Override
    public void apply(final ActivationDecisionHandler handler) {
        handler.handle(this);
    }
}
