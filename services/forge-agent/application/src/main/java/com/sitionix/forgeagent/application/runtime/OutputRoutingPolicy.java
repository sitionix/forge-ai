package com.sitionix.forgeagent.application.runtime;

public interface OutputRoutingPolicy {

    boolean supports(OutputRoutingContext context);

    OutputRoutingDecision route(OutputRoutingContext context);
}
