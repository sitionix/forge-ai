package com.sitionix.forgeagent.application.runtime;

public final class TerminalRoutingDecision implements OutputRoutingDecision {

    @Override
    public void apply(final OutputRoutingDecisionHandler handler) {
        handler.handle(this);
    }
}
