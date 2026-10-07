package com.sitionix.forgeagent.application.dialogue;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record DialogueProperties(int maxTurns, int maxMessageCodePoints, int maxInitialInputCodePoints) {
    public DialogueProperties(
            @Value("${forge.agent.dialogue.max-dialogue-turns-per-node-run:100}") int maxTurns,
            @Value("${forge.agent.dialogue.max-message-code-points:16000}") int maxMessageCodePoints,
            @Value("${forge.agent.dialogue.max-initial-input-code-points:128000}") int maxInitialInputCodePoints) {
        if (maxTurns < 1 || maxMessageCodePoints < 1 || maxInitialInputCodePoints < 1) {
            throw new IllegalArgumentException("Dialogue limits must be positive.");
        }
        this.maxTurns = maxTurns;
        this.maxMessageCodePoints = maxMessageCodePoints;
        this.maxInitialInputCodePoints = maxInitialInputCodePoints;
    }
}
