package com.sitionix.forgeagent.application.runtime;

public interface ActivationDecisionHandler {

    void handle(WaitActivationDecision decision);

    void handle(CloseActivationDecision decision);

    void handle(ActivateNodeDecision decision);
}
