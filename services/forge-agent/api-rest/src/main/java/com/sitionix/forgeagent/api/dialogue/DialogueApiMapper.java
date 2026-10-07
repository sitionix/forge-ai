package com.sitionix.forgeagent.api.dialogue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.dialogue.DialogueProperties;
import com.sitionix.forgeagent.domain.model.dialogue.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DialogueApiMapper {
    private final ObjectMapper json;
    private final DialogueProperties properties;

    public DialogueApiDtos.State response(final DialogueReadState value) {
        final var s = value.snapshot();
        return new DialogueApiDtos.State(s.nodeRunId(),s.state().name(),s.revision(),s.summaryRevisionId(),
                revision(s.latestRevision()),turn(s.activeTurn()),completion(s.completion()),page(value.messages()),
                s.lastMessageSequence(),s.turnCount(),properties.maxTurns(),s.createdAt(),s.updatedAt());
    }

    public DialogueApiDtos.MessagePage page(final DialogueMessagePage page) {
        return new DialogueApiDtos.MessagePage(page.messages().stream().map(m -> new DialogueApiDtos.Message(m.id(),m.sequence(),
                m.role().name(),m.text(),m.turnId(),m.createdAt())).toList(),page.nextSequence(),page.hasMore());
    }

    private DialogueApiDtos.Revision revision(final DialogueRevision r) {
        if (r == null) return null;
        try {
            return new DialogueApiDtos.Revision(r.id(),r.revision(),r.turnId(),r.kind().name(),r.inputRevision(),
                    json.readValue(r.resultJson(),DialogueApiDtos.Reply.class),r.createdAt());
        } catch (JsonProcessingException corrupted) { throw new IllegalStateException("Persisted Dialogue reply is invalid.",corrupted); }
    }

    private DialogueApiDtos.Turn turn(final DialogueTurn t) {
        return t == null ? null : new DialogueApiDtos.Turn(t.id(),t.kind().name(),t.requestId(),t.triggeringMessageId(),t.inputRevision(),
                t.status().name(),t.executionTurnId(),t.failureCode(),t.failureMessage(),t.createdAt(),t.finishedAt());
    }

    private DialogueApiDtos.Completion completion(final DialogueCompletion c) {
        return c == null ? null : new DialogueApiDtos.Completion(c.requestId(),c.summaryRevisionId(),c.outputPortId(),
                c.disposition().name(),c.revision(),c.completedAt());
    }
}
