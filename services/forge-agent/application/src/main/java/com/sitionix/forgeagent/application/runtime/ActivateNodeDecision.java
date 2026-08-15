package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import java.util.List;
import java.util.UUID;

public record ActivateNodeDecision(
        UUID workflowRunId,
        UUID activationFrameId,
        UUID targetInputPortId,
        List<ConnectionResolution> delivered
) implements ActivationDecision {

    @Override
    public void apply(final ActivationDecisionHandler handler) {
        handler.handle(this);
    }
}
