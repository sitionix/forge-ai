package com.sitionix.forgeagent.application.runtime;

public sealed interface OutputRoutingDecision permits TerminalRoutingDecision, SelectedOutputRoutingDecision {

    void apply(OutputRoutingDecisionHandler handler);
}
