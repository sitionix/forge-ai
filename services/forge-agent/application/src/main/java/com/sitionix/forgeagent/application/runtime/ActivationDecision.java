package com.sitionix.forgeagent.application.runtime;

public sealed interface ActivationDecision permits WaitActivationDecision, CloseActivationDecision, ActivateNodeDecision {

    void apply(ActivationDecisionHandler handler);
}
