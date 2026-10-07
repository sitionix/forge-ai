package com.sitionix.forgeagent.application.dialogue;

import com.sitionix.forgeagent.domain.model.dialogue.DialogueTurnKind;
import java.util.UUID;

/** Forge-owned provenance, separate from user requirement text. */
public record DialogueExecutionContext(UUID dialogueTurnId, DialogueTurnKind kind, long inputRevision, String requestJson) { }
