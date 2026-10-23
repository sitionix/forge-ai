package com.sitionix.forgeagent.application.runtime;
import com.sitionix.forgeagent.domain.model.execution.EffectiveAgentDefinition;
import com.sitionix.forgeagent.domain.model.McpExecutionSelection;
import com.sitionix.forgeagent.domain.model.McpRuntimeLaunchGrants;
import java.util.UUID;
public record AgentTurnExecutionRequest(UUID executionId, EffectiveAgentDefinition definition, String input,
        String outputSchemaJson, ExecutionWorkspace workspace, Mode mode, String conversationId,
        String providerVersion, boolean sharedSessionGroup, McpExecutionSelection mcpSelection,
        McpRuntimeLaunchGrants grants, AgentTurnIdentityCallbacks identities,
        com.sitionix.forgeagent.domain.model.execution.AgentExecutionOwner owner) {
    public AgentTurnExecutionRequest(UUID executionId, EffectiveAgentDefinition definition,String input,String outputSchemaJson,
        ExecutionWorkspace workspace,Mode mode,String conversationId,String providerVersion,boolean sharedSessionGroup,
        McpExecutionSelection mcpSelection,McpRuntimeLaunchGrants grants,AgentTurnIdentityCallbacks identities) {
        this(executionId,definition,input,outputSchemaJson,workspace,mode,conversationId,providerVersion,sharedSessionGroup,mcpSelection,grants,identities,null);
    }
    public enum Mode { FRESH, TRACKED_FRESH, DURABLE }
}
