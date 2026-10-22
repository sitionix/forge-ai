package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.domain.model.AgentExecutionProviderCapability;
import com.sitionix.forgeagent.domain.port.AgentExecutionProviderCapabilities;
import org.springframework.stereotype.Component;

@Component
final class CodexProviderCapabilities implements AgentExecutionProviderCapabilities {
    private final CodexClient client;
    private final boolean nativeSubagents;
    CodexProviderCapabilities(CodexClient client,@org.springframework.beans.factory.annotation.Value("${forge.agent.standalone.native-subagents-enabled:true}")boolean nativeSubagents){this.client=client;this.nativeSubagents=nativeSubagents;}
    @Override
    public boolean supports(final String providerId, final AgentExecutionProviderCapability capability) {
        if(!"codex".equals(providerId))return false;
        if(capability==AgentExecutionProviderCapability.DURABLE_CONTEXT)return true;
        if(capability==AgentExecutionProviderCapability.NATIVE_SUBAGENTS && nativeSubagents) {
            try{return "0.163.0".equals(client.version());}catch(RuntimeException unavailable){return false;}
        }
        return false;
    }
}
