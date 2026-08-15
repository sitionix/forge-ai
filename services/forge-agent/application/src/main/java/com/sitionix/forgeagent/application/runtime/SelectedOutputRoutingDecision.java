package com.sitionix.forgeagent.application.runtime;

import java.util.UUID;

public record SelectedOutputRoutingDecision(UUID selectedOutputPortId) implements OutputRoutingDecision {

    @Override
    public void apply(final OutputRoutingDecisionHandler handler) {
        handler.handle(this);
    }
}
