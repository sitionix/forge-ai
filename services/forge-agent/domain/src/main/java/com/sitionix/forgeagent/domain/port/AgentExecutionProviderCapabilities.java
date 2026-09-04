package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.AgentExecutionProviderCapability;

public interface AgentExecutionProviderCapabilities {
    boolean supports(String providerId, AgentExecutionProviderCapability capability);
}
