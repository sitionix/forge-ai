package com.sitionix.forgeagent.domain.model.dialogue;

import java.time.Instant;
import java.util.UUID;

public record DialogueCompletion(UUID nodeRunId, UUID requestId, UUID summaryRevisionId, UUID outputPortId, DialogueOutputDisposition disposition, long revision, Instant completedAt) { }
