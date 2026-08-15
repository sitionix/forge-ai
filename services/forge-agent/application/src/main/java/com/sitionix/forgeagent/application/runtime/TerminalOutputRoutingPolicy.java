package com.sitionix.forgeagent.application.runtime;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class TerminalOutputRoutingPolicy implements OutputRoutingPolicy {

    @Override
    public boolean supports(final OutputRoutingContext context) {
        return context.availableOutputs().isEmpty();
    }

    @Override
    public OutputRoutingDecision route(final OutputRoutingContext context) {
        return new TerminalRoutingDecision();
    }
}
