package com.sitionix.forgeagent.application.runtime;

public interface OutputRoutingDecisionHandler {

    void handle(TerminalRoutingDecision decision);

    void handle(SelectedOutputRoutingDecision decision);
}
