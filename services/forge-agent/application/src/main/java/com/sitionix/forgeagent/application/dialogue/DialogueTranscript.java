package com.sitionix.forgeagent.application.dialogue;

import com.sitionix.forgeagent.domain.model.dialogue.DialogueMessage;
import com.sitionix.forgeagent.domain.model.dialogue.DialogueMessageRole;
import com.sitionix.forgeagent.domain.port.dialogue.DialogueRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DialogueTranscript {
    private final DialogueRepository dialogues;

    public List<DialogueMessage> messages(final UUID nodeRunId) {
        final List<DialogueMessage> all = new ArrayList<>();
        long cursor = 0;
        while (true) {
            final var page = dialogues.messages(nodeRunId,cursor,200);
            all.addAll(page);
            if (page.size() < 200) return List.copyOf(all);
            cursor = page.getLast().sequence();
        }
    }

    public Set<UUID> userMessageIds(final UUID nodeRunId) {
        return this.messages(nodeRunId).stream().filter(m -> m.role() == DialogueMessageRole.USER)
                .map(DialogueMessage::id).collect(Collectors.toUnmodifiableSet());
    }
}
