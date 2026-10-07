package com.sitionix.forgeagent.domain.model.dialogue;

import java.time.Instant;
import java.util.UUID;

public record DialogueTurn(UUID id, UUID nodeRunId, DialogueTurnKind kind, UUID requestId, UUID triggeringMessageId, long inputRevision, DialogueTurnStatus status, UUID executionTurnId, String resultJson, String failureCode, String failureMessage, Instant createdAt, Instant finishedAt) { }
