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
        McpExecutionSelection mcpSelection
) {
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
