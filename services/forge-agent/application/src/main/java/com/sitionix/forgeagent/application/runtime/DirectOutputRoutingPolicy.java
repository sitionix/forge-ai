package com.sitionix.forgeagent.application.runtime;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
public class DirectOutputRoutingPolicy implements OutputRoutingPolicy {

    @Override
    public boolean supports(final OutputRoutingContext context) {
        return context.availableOutputs().size() == 1;
    }

    @Override
    public OutputRoutingDecision route(final OutputRoutingContext context) {
        return new SelectedOutputRoutingDecision(context.availableOutputs().get(0).sourcePortId());
    }
}
