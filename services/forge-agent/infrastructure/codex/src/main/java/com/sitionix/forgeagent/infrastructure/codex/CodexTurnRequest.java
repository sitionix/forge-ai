package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;

record CodexTurnRequest(
        String userInput,
        String developerInstructions,
        String modelId,
        String effortId,
        JsonNode outputSchema,
        ExecutionWorkspace executionWorkspace
) {
}
