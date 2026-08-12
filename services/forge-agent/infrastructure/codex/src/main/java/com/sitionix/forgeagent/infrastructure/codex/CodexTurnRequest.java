package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;

record CodexTurnRequest(
        String prompt,
        String modelId,
        String effortId,
        JsonNode outputSchema
) {
}
