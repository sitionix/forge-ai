package com.sitionix.forgeagent.domain.port.dialogue;

import com.sitionix.forgeagent.domain.model.dialogue.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Mutations require the owning workflow and node locks in an enclosing transaction. */
public interface DialogueRepository {
    void create(UUID nodeRunId);
    Optional<DialogueSnapshot> find(UUID nodeRunId);
    DialogueSnapshot lock(UUID nodeRunId);
    List<DialogueMessage> messages(UUID nodeRunId, long afterSequence, int limit);
    void appendMessage(DialogueMessage message);
    void insertTurn(DialogueTurn turn);
    Optional<DialogueTurn> findTurn(UUID turnId);
    List<UUID> queuedTurnIds();
    void startTurn(UUID turnId);
    void finishTurn(UUID turnId, DialogueTurnStatus status, String resultJson, String failureCode, String failureMessage);
    void appendRevision(DialogueRevision revision);
    void updateState(UUID nodeRunId, DialogueState state, long revision, UUID summaryRevisionId);
    Optional<DialogueCommand> findCommand(UUID nodeRunId, UUID requestId);
    void recordCommand(DialogueCommand command);
    void complete(DialogueCompletion completion);
}
