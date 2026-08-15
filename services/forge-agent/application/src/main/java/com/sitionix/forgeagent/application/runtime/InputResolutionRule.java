package com.sitionix.forgeagent.application.runtime;

public interface InputResolutionRule {

    boolean supports(InputParticipation participation);

    ActivationDecision decision(InputParticipation participation);
}
