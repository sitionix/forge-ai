package com.sitionix.forgeai.domain.model.codex;

import lombok.Builder;

@Builder(toBuilder = true)
public record CodexTurnResponse(
        String sessionId,
        String threadId,
        String turnId,
        String assistantResponse
) {
}
