package com.sitionix.forgeagent.domain.model.dialogue;

import java.time.Instant;
import java.util.UUID;

public record DialogueSnapshot(UUID nodeRunId, DialogueState state, long revision, UUID summaryRevisionId, DialogueRevision latestRevision, DialogueTurn activeTurn, DialogueCompletion completion, long lastMessageSequence, int turnCount, Instant createdAt, Instant updatedAt) { }
