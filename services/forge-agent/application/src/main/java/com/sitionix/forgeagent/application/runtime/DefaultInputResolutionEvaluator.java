package com.sitionix.forgeagent.application.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultInputResolutionEvaluator implements InputResolutionEvaluator {

    private final InputResolutionRuleRegistry ruleRegistry;

    @Override
    public ActivationDecision evaluate(final InputParticipation participation) {
        return this.ruleRegistry.evaluate(participation);
    }
}
