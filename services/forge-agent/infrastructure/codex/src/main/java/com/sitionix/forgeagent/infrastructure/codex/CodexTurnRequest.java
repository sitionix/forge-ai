package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;

record CodexTurnRequest(
        String userInput,
        String developerInstructions,
        String modelId,
        String effortId,
        JsonNode outputSchema
) {
}
