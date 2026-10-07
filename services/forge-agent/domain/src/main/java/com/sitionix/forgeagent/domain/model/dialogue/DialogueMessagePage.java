package com.sitionix.forgeagent.domain.model.dialogue;

import java.util.List;

public record DialogueMessagePage(List<DialogueMessage> messages, long nextSequence, boolean hasMore) {
    public DialogueMessagePage { messages = List.copyOf(messages); }
}
