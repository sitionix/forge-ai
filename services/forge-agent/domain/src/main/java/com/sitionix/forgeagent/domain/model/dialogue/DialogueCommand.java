package com.sitionix.forgeagent.domain.model.dialogue;

import java.time.Instant;
import java.util.UUID;

public record DialogueCommand(UUID nodeRunId, UUID requestId, String kind, String fingerprint, Instant createdAt) { }
