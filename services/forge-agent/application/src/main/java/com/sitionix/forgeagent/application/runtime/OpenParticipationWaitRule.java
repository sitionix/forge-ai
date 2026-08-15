package com.sitionix.forgeagent.application.runtime;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class OpenParticipationWaitRule implements InputResolutionRule {

    @Override
    public boolean supports(final InputParticipation participation) {
        return participation.open();
    }

    @Override
    public ActivationDecision decision(final InputParticipation participation) {
        return new WaitActivationDecision();
    }
}
