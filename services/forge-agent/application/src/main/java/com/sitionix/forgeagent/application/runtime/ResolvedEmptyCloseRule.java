package com.sitionix.forgeagent.application.runtime;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
public class ResolvedEmptyCloseRule implements InputResolutionRule {

    @Override
    public boolean supports(final InputParticipation participation) {
        return participation.delivered().isEmpty();
    }

    @Override
    public ActivationDecision decision(final InputParticipation participation) {
        return new CloseActivationDecision(
                participation.workflowRunId(),
                participation.activationFrameId(),
                participation.targetInputPortId()
        );
    }
}
