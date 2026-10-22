package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;
import com.sitionix.forgeagent.domain.model.McpExecutionSelection;

record CodexTurnRequest(
        String userInput,
        String developerInstructions,
        String modelId,
        String effortId,
        JsonNode outputSchema,
        ExecutionWorkspace executionWorkspace,
        boolean sharedSessionGroup,
        McpExecutionSelection mcpSelection,
        boolean nativeSubagents,
        java.util.Map<String,String> resourceSnapshot
) {
    CodexTurnRequest(String userInput,String developerInstructions,String modelId,String effortId,JsonNode outputSchema,
            ExecutionWorkspace workspace,boolean sharedSessionGroup,McpExecutionSelection selection,boolean nativeSubagents) {
        this(userInput,developerInstructions,modelId,effortId,outputSchema,workspace,sharedSessionGroup,selection,nativeSubagents,null);
    }
    CodexTurnRequest(String userInput,String developerInstructions,String modelId,String effortId,JsonNode outputSchema,
            ExecutionWorkspace workspace,boolean sharedSessionGroup,McpExecutionSelection mcpSelection) {
        this(userInput,developerInstructions,modelId,effortId,outputSchema,workspace,sharedSessionGroup,mcpSelection,false);
    }
    CodexTurnRequest(final String userInput, final String developerInstructions, final String modelId,
                     final String effortId, final JsonNode outputSchema, final ExecutionWorkspace executionWorkspace,
                     final boolean sharedSessionGroup) {
        this(userInput, developerInstructions, modelId, effortId, outputSchema, executionWorkspace,
                sharedSessionGroup, null);
    }

    CodexTurnRequest(final String userInput, final String developerInstructions, final String modelId,
                     final String effortId, final JsonNode outputSchema, final ExecutionWorkspace executionWorkspace) {
        this(userInput, developerInstructions, modelId, effortId, outputSchema, executionWorkspace, false, null);
    }
}
