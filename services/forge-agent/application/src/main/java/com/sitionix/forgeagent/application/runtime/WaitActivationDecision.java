package com.sitionix.forgeagent.application.runtime;

public final class WaitActivationDecision implements ActivationDecision {

    @Override
    public void apply(final ActivationDecisionHandler handler) {
        handler.handle(this);
    }
}
