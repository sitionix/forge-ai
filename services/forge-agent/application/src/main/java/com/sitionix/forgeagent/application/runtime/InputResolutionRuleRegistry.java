package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InputResolutionRuleRegistry {

    private final List<InputResolutionRule> rules;

    public ActivationDecision evaluate(final InputParticipation participation) {
        return this.rules.stream()
                .filter(rule -> rule.supports(participation))
                .findFirst()
                .orElseThrow(() -> new ConflictException("INPUT_RESOLUTION_RULE_NOT_FOUND", "No input resolution rule supports this activation."))
                .decision(participation);
    }
}
