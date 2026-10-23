package com.sitionix.forgeagent.application.definition;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;
import com.sitionix.forgeagent.domain.model.AgentModelSelection;
import com.sitionix.forgeagent.domain.model.execution.EffectiveAgentDefinition;
import java.util.UUID;
public interface NativeAgentDefinitionPort {
    EffectiveAgentDefinition resolve(UUID project,String reference,ExecutionWorkspace workspace,AgentModelSelection fallback);
    void revalidate(EffectiveAgentDefinition snapshot,ExecutionWorkspace workspace);
    java.util.Map<String,String> resources(ExecutionWorkspace workspace);
}
