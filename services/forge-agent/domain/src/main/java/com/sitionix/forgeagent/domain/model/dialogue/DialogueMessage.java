package com.sitionix.forgeagent.domain.model.dialogue;

import java.time.Instant;
import java.util.UUID;

public record DialogueMessage(UUID id, UUID nodeRunId, long sequence, DialogueMessageRole role, String text, UUID turnId, Instant createdAt) { }
