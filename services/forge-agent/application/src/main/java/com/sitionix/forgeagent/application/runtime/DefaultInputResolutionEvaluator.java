package com.sitionix.forgeagent.application.runtime;

import org.springframework.stereotype.Component;

@Component
public class DefaultInputResolutionEvaluator implements InputResolutionEvaluator {

    @Override
    public ActivationDecision evaluate(final InputParticipation participation) {
        if (participation.open()) {
            return new WaitActivationDecision();
        }
        if (participation.delivered().isEmpty()) {
            return new CloseActivationDecision(participation.workflowRunId(), participation.activationFrameId(), participation.targetInputPortId());
        }
        return new ActivateNodeDecision(participation.workflowRunId(), participation.activationFrameId(), participation.targetInputPortId(), participation.delivered());
    }
}
