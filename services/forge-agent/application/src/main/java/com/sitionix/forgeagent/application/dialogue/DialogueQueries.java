package com.sitionix.forgeagent.application.dialogue;

import com.sitionix.forgeagent.domain.model.dialogue.DialogueReadState;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DialogueQueries {
    private final DialogueCommands commands;

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ, propagation=Propagation.REQUIRES_NEW)
    public DialogueReadState read(final UUID runId, final UUID nodeRunId) {
        return new DialogueReadState(commands.get(runId,nodeRunId),commands.messages(runId,nodeRunId,0,100));
    }
}
