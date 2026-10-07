package com.sitionix.forgeagent.application.dialogue;

import com.sitionix.forgeagent.application.mcp.McpGatewayService;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.model.dialogue.*;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.dialogue.DialogueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Cancellation runs in the owning workflow transaction. Expired recovery uses the same persisted execution engine. */
@Service
@RequiredArgsConstructor
public class DialogueRecoveryHandler {
    private final DialogueRepository dialogues;
    private final AgentExecutionSessionRepository sessions;
    private final NodeRunRepository nodes;
    private final ObjectProvider<McpGatewayService> gateway;

    public boolean needsProviderCancellation(final NodeRun node) {
        return dialogues.find(node.id()).map(snapshot -> snapshot.activeTurn() != null
                && snapshot.activeTurn().status() == DialogueTurnStatus.RUNNING).orElse(false);
    }

    public void cancel(final NodeRun node) {
        nodes.findByIdForUpdate(node.id());
        final var found = dialogues.find(node.id());
        if (found.isEmpty()) return;
        final var snapshot = dialogues.lock(node.id());
        if (snapshot.state() == DialogueState.COMPLETED || snapshot.state() == DialogueState.CANCELLED
                || snapshot.state() == DialogueState.FAILED) return;
        final var active = snapshot.activeTurn();
        if (active != null && active.status() == DialogueTurnStatus.RUNNING) {
            if (!sessions.cancel(node.id())) {
                throw new ConflictException("DIALOGUE_CANCELLATION_CONFLICT", "The dialogue execution changed during cancellation.");
            }
        } else sessions.closeDialogueSession(node.id(),AgentExecutionTerminalOutcome.CANCELLED);
        if (active != null) {
            dialogues.finishTurn(active.id(),DialogueTurnStatus.CANCELLED,null,null,null);
            final var enabledGateway = gateway.getIfAvailable();
            if (enabledGateway != null && active.executionTurnId() != null) enabledGateway.revokeExecution(active.executionTurnId());
        }
        dialogues.updateState(node.id(),DialogueState.CANCELLED,snapshot.revision()+1,null);
    }
}
