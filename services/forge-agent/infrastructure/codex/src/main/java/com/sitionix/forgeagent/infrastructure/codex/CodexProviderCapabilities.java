package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.domain.model.AgentExecutionProviderCapability;
import com.sitionix.forgeagent.domain.port.AgentExecutionProviderCapabilities;
import org.springframework.stereotype.Component;

@Component
final class CodexProviderCapabilities implements AgentExecutionProviderCapabilities {
    @Override
    public boolean supports(final String providerId, final AgentExecutionProviderCapability capability) {
        return "codex".equals(providerId) && capability == AgentExecutionProviderCapability.DURABLE_CONTEXT;
    }
}
