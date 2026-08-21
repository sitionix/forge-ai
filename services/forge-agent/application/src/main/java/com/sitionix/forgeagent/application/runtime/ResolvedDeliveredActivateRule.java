package com.sitionix.forgeagent.application.runtime;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(30)
public class ResolvedDeliveredActivateRule implements InputResolutionRule {

    @Override
    public boolean supports(final InputParticipation participation) {
        return true;
    }

    @Override
    public ActivationDecision decision(final InputParticipation participation) {
        return new ActivateNodeDecision(
                participation.workflowRunId(),
                participation.activationFrameId(),
                participation.targetInputPortId(),
                participation.delivered(),
                participation.repositoryId()
        );
    }
}
