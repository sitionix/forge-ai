package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;

record CodexTurnRequest(
        String userInput,
        String developerInstructions,
        String modelId,
        String effortId,
        JsonNode outputSchema,
        ExecutionWorkspace executionWorkspace,
        boolean sharedSessionGroup
) {
    CodexTurnRequest(final String userInput, final String developerInstructions, final String modelId,
                     final String effortId, final JsonNode outputSchema, final ExecutionWorkspace executionWorkspace) {
        this(userInput, developerInstructions, modelId, effortId, outputSchema, executionWorkspace, false);
    }
}
