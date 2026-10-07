package com.sitionix.forgeagent.application.dialogue;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.model.dialogue.*;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import com.sitionix.forgeagent.domain.port.dialogue.DialogueRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DialogueCommands {
    private final DialogueRepository dialogues;
    private final AgentExecutionSessionRepository sessions;
    private final NodeRunRepository nodes;
    private final WorkflowRunRepository workflows;
    private final DialogueInvocationLock locks;
    private final DialogueCommandValidation validation;
    private final Clock clock;
    private final DialogueCompletionPolicy completionPolicy;
    private final DialogueTranscript transcript;
    private final com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository graphs;

    public DialogueSnapshot get(final UUID runId, final UUID nodeRunId) {
        this.requireOwner(runId,nodeRunId);
        return dialogues.find(nodeRunId).orElseThrow(() -> new NotFoundException("DIALOGUE_NOT_FOUND", "Dialogue has not started yet."));
    }

    public DialogueMessagePage messages(final UUID runId, final UUID nodeRunId, final long afterSequence, final int limit) {
        this.requireOwner(runId,nodeRunId);
        if (afterSequence < 0 || limit < 1 || limit > 200) {
            throw new ValidationException("INVALID_DIALOGUE_CURSOR", "A nonnegative cursor and a limit between 1 and 200 are required.");
        }
        final var found = dialogues.messages(nodeRunId,afterSequence,limit+1);
        final boolean more = found.size() > limit;
        final var page = more ? found.subList(0,limit) : found;
        return new DialogueMessagePage(page,page.isEmpty() ? afterSequence : page.getLast().sequence(),more);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void initialize(final UUID nodeRunId) {
        final var runId = nodes.findWorkflowRunIdById(nodeRunId);
        if (runId.isEmpty()) return;
        final var target = locks.lock(runId.get(),nodeRunId);
        if (target.node().status() != NodeRunStatus.PENDING || target.node().executionFrameId() == null
                || (target.run().status() != WorkflowRunStatus.QUEUED && target.run().status() != WorkflowRunStatus.RUNNING)) return;
        if (dialogues.find(nodeRunId).isPresent()) return;
        dialogues.create(nodeRunId);
        final var snapshot = dialogues.lock(nodeRunId);
        this.queue(target,snapshot,DialogueTurnKind.INITIAL,null,null);
    }

    @Transactional
    public DialogueSnapshot send(final UUID runId, final UUID nodeRunId, final UUID requestId,
                                 final long expectedRevision, final String text) {
        return this.command(runId,nodeRunId,requestId,expectedRevision,DialogueTurnKind.CHAT,text).snapshot();
    }

    @Transactional
    public DialogueSnapshot summarize(final UUID runId, final UUID nodeRunId, final UUID requestId,
                                      final long expectedRevision) {
        return this.command(runId,nodeRunId,requestId,expectedRevision,DialogueTurnKind.SUMMARY,null).snapshot();
    }

    @Transactional
    public DialogueCommandReceipt sendReceipt(final UUID runId, final UUID nodeRunId, final UUID requestId,
                                               final long expectedRevision, final String text) {
        return this.command(runId,nodeRunId,requestId,expectedRevision,DialogueTurnKind.CHAT,text);
    }

    @Transactional
    public DialogueCommandReceipt summarizeReceipt(final UUID runId, final UUID nodeRunId, final UUID requestId,
                                                    final long expectedRevision) {
        return this.command(runId,nodeRunId,requestId,expectedRevision,DialogueTurnKind.SUMMARY,null);
    }

    @Transactional
    public DialogueSnapshot complete(final UUID runId, final UUID nodeRunId, final UUID requestId,
                                     final long expectedRevision, final UUID summaryRevisionId, final UUID outputPortId) {
        validation.command(requestId,expectedRevision);
        final var target = locks.lock(runId,nodeRunId);
        final var snapshot = dialogues.lock(nodeRunId);
        final String fingerprint = validation.fingerprint("COMPLETE",expectedRevision,summaryRevisionId + ":" + outputPortId);
        if (this.replayed(nodeRunId,requestId,fingerprint)) return snapshot;
        DialogueInvocationLock.requireActive(target);
        this.requireRevision(snapshot,expectedRevision);
        if (target.node().status() != NodeRunStatus.WAITING_FOR_DIALOGUE) {
            throw new ConflictException("DIALOGUE_TURN_IN_PROGRESS", "Wait for the current dialogue turn to finish.");
        }
        if (outputPortId == null) throw new ValidationException("INVALID_DIALOGUE_OUTPUT", "A Dialogue output is required.");
        final var port = graphs.findPort(runId,outputPortId)
                .filter(p -> p.sourceNodeId().equals(target.node().sourceNodeId()) && p.direction() == PortDirection.OUTPUT
                        && p.dialogueDisposition() != null)
                .orElseThrow(() -> new ValidationException("INVALID_DIALOGUE_OUTPUT", "The output does not belong to this snapshotted Dialogue node."));
        final var reply = completionPolicy.validate(target.node().agentOutputSchema(),snapshot,summaryRevisionId,
                port.dialogueDisposition(),transcript.userMessageIds(nodeRunId));
        final Instant now = Instant.now(clock);
        final var completion = new DialogueCompletion(nodeRunId,requestId,summaryRevisionId,outputPortId,
                port.dialogueDisposition(),snapshot.revision()+1,now);
        if (!sessions.closeDialogueSession(nodeRunId,AgentExecutionTerminalOutcome.SUCCEEDED)) {
            throw new ConflictException("DIALOGUE_SESSION_BUSY", "The dialogue session could not be closed safely.");
        }
        dialogues.complete(completion);
        dialogues.recordCommand(new DialogueCommand(nodeRunId,requestId,"COMPLETE",fingerprint,now));
        dialogues.updateState(nodeRunId,DialogueState.COMPLETED,completion.revision(),summaryRevisionId);
        nodes.saveAndFlush(DialogueNodeTransitions.node(target.node(),NodeRunStatus.SUCCEEDED,now,
                completionPolicy.output(reply,completion,snapshot.latestRevision().revision(),runId),null,outputPortId));
        return dialogues.lock(nodeRunId);
    }

    private DialogueCommandReceipt command(final UUID runId, final UUID nodeRunId, final UUID requestId,
                                     final long expectedRevision, final DialogueTurnKind kind, final String text) {
        validation.command(requestId,expectedRevision);
        final var target = locks.lock(runId,nodeRunId);
        final var snapshot = dialogues.lock(nodeRunId);
        final String fingerprint = validation.fingerprint(kind.name(),expectedRevision,text);
        if (this.replayed(nodeRunId,requestId,fingerprint)) return new DialogueCommandReceipt(snapshot,true);
        DialogueInvocationLock.requireActive(target);
        this.requireRevision(snapshot,expectedRevision);
        if (snapshot.activeTurn() != null || target.node().status() != NodeRunStatus.WAITING_FOR_DIALOGUE) {
            throw new ConflictException("DIALOGUE_TURN_IN_PROGRESS", "Wait for the current dialogue turn to finish.");
        }
        if (kind == DialogueTurnKind.CHAT) validation.text(text);
        validation.turnBudget(snapshot.turnCount());
        this.queue(target,snapshot,kind,requestId,text);
        dialogues.recordCommand(new DialogueCommand(nodeRunId,requestId,kind.name(),fingerprint,Instant.now(clock)));
        return new DialogueCommandReceipt(dialogues.lock(nodeRunId),false);
    }

    private void queue(final DialogueInvocationLock.Target target, final DialogueSnapshot snapshot,
                       final DialogueTurnKind kind, final UUID requestId, final String text) {
        final Instant now = Instant.now(clock);
        final long nextRevision = snapshot.revision()+1;
        final UUID turnId = UUID.randomUUID();
        final UUID messageId = kind == DialogueTurnKind.CHAT ? UUID.randomUUID() : null;
        final NodeRun node = target.node();
        if (messageId != null) {
            dialogues.appendMessage(new DialogueMessage(messageId,node.id(),snapshot.lastMessageSequence()+1,
                    DialogueMessageRole.USER,text,turnId,now));
        }
        dialogues.insertTurn(new DialogueTurn(turnId,node.id(),kind,requestId,messageId,nextRevision,
                DialogueTurnStatus.QUEUED,null,null,null,null,now,null));
        nodes.saveAndFlush(DialogueNodeTransitions.node(node,NodeRunStatus.RUNNING,now,node.output(),node.failure(),node.selectedOutputPortId()));
        sessions.allocateDialogueTurn(node,turnId,node.executionModel().providerId());
        dialogues.updateState(node.id(),DialogueState.RUNNING,nextRevision,null);
        if (target.run().status() == WorkflowRunStatus.QUEUED) workflows.saveLifecycle(DialogueNodeTransitions.running(target.run(),now));
    }

    boolean replayed(final UUID nodeRunId, final UUID requestId, final String fingerprint) {
        final var previous = dialogues.findCommand(nodeRunId,requestId);
        if (previous.isEmpty()) return false;
        if (!fingerprint.equals(previous.get().fingerprint())) {
            throw new ConflictException("DIALOGUE_REQUEST_CONFLICT", "This request ID was already used with a different payload.");
        }
        return true;
    }

    static void requireRevision(final DialogueSnapshot snapshot, final long expectedRevision) {
        if (snapshot.revision() != expectedRevision) {
            throw new ConflictException("DIALOGUE_REVISION_CONFLICT", "The dialogue changed; reload its current revision.");
        }
    }

    private void requireOwner(final UUID runId, final UUID nodeRunId) {
        final var node = nodes.findById(nodeRunId).filter(n -> runId.equals(n.workflowRunId()))
                .orElseThrow(() -> new NotFoundException("NODE_RUN_NOT_FOUND", "Node run was not found in the workflow run."));
        DialogueInvocationLock.requireDialogue(node);
    }
}
