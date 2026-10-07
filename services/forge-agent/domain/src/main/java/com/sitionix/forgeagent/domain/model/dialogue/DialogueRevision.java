package com.sitionix.forgeagent.domain.model.dialogue;

import java.time.Instant;
import java.util.UUID;

public record DialogueRevision(UUID id, UUID nodeRunId, long revision, UUID turnId, DialogueTurnKind kind, long inputRevision, String resultJson, Instant createdAt) { }
