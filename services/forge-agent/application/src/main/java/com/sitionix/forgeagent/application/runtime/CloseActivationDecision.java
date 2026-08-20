package com.sitionix.forgeagent.application.runtime;

import java.util.UUID;

public record CloseActivationDecision(UUID workflowRunId, UUID activationFrameId, UUID targetInputPortId,
                                      UUID repositoryId) implements ActivationDecision {
    @Override
    public void apply(final ActivationDecisionHandler handler) {
        handler.handle(this);
    }
}
