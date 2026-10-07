package com.sitionix.forgeagent.application.dialogue;

import com.sitionix.forgeagent.application.runtime.*;
import com.sitionix.forgeagent.domain.exception.ForgeAgentException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.model.dialogue.*;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.dialogue.DialogueRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DialogueTurnLifecycle {
    private final DialogueRepository dialogues;
    private final NodeRunRepository nodes;
    private final AgentExecutionSessionRepository sessions;
    private final DialogueInvocationLock locks;
    private final AgentSessionLeaseService leases;
    private final ExecutionWorkspaceResolver workspaces;
    private final DialogueExecutionRequestFactory requests;
    private final DialogueTurnResultPolicy results;
    private final DialogueTranscript transcript;
    private final WorkflowExecutionCoordinator coordinator;
    private final Clock clock;
    private final String ownerId = UUID.randomUUID().toString();

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<DialogueExecutionRequest> claim(final UUID turnId) {
        final var found = dialogues.findTurn(turnId);
        if (found.isEmpty() || found.get().status() != DialogueTurnStatus.QUEUED) return Optional.empty();
        final DialogueTurn turn = found.get();
        final var runId = nodes.findWorkflowRunIdById(turn.nodeRunId());
        if (runId.isEmpty()) return Optional.empty();
        final var target = locks.lock(runId.get(),turn.nodeRunId());
        final var snapshot = dialogues.lock(turn.nodeRunId());
        if (target.node().status() != NodeRunStatus.RUNNING || !DialogueInvocationLock.activeWorkflow(target.run())
                || snapshot.activeTurn() == null || !turnId.equals(snapshot.activeTurn().id())
                || snapshot.activeTurn().status() != DialogueTurnStatus.QUEUED) return Optional.empty();
        final ExecutionWorkspace workspace;
        final DialogueExecutionRequestFactory.Prepared prepared;
        try {
            workspace = workspaces.resolve(target.run(),target.node());
            prepared = requests.prepare(target,turn);
        } catch (RuntimeException preparationFailure) {
            final String code = preparationFailure instanceof ForgeAgentException typed ? typed.code()
                    : preparationFailure instanceof ExecutionWorkspaceException ? "EXECUTION_WORKSPACE_UNAVAILABLE" : "DIALOGUE_PREPARATION_FAILED";
            final var failure = new NodeRunFailure(code,"Dialogue execution could not be prepared.");
            dialogues.finishTurn(turnId,DialogueTurnStatus.FAILED,null,failure.code(),failure.message());
            sessions.closeDialogueSession(turn.nodeRunId(),AgentExecutionTerminalOutcome.FAILED);
            this.failed(target,snapshot,failure);
            return Optional.empty();
        }
        final var session = leases.claimTurn(turn.executionTurnId(),ownerId);
        if (session.isEmpty()) return Optional.empty();
        dialogues.startTurn(turnId);
        dialogues.updateState(turn.nodeRunId(),DialogueState.RUNNING,snapshot.revision()+1,null);
        return Optional.of(requests.bind(target,prepared,workspace,session.get()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean succeed(final DialogueExecutionRequest request, final AgentExecutionResult execution) {
        final var claim = request.executionClaim();
        final var context = claim.dialogueContext();
        final var target = locks.lock(claim.workflowRunId(),claim.nodeRunId());
        final var snapshot = dialogues.lock(claim.nodeRunId());
        if (!current(target,snapshot,request)) return false;
        leases.lockCurrent(claim.agentSessionClaim());
        if (execution == null || execution.output() == null || execution.selectedOutputPortId() != null) {
            throw new ValidationException("INVALID_DIALOGUE_RESULT","Dialogue execution returned no valid reply.");
        }
        final var reply = results.validate(target.node().agentOutputSchema(),context.kind(),execution.output().jsonValue(),
                transcript.userMessageIds(claim.nodeRunId()));
        final Instant now = Instant.now(clock);
        final long revision = snapshot.revision()+1;
        final UUID revisionId = UUID.randomUUID();
        dialogues.appendMessage(new DialogueMessage(UUID.randomUUID(),claim.nodeRunId(),snapshot.lastMessageSequence()+1,
                DialogueMessageRole.ASSISTANT,reply.path("message").textValue(),context.dialogueTurnId(),now));
        dialogues.finishTurn(context.dialogueTurnId(),DialogueTurnStatus.SUCCEEDED,reply.toString(),null,null);
        dialogues.appendRevision(new DialogueRevision(revisionId,claim.nodeRunId(),revision,context.dialogueTurnId(),
                context.kind(),context.inputRevision(),reply.toString(),now));
        leases.finish(claim.agentSessionClaim(),AgentExecutionTurnStatus.SUCCEEDED,null,null,false);
        dialogues.updateState(claim.nodeRunId(),context.kind() == DialogueTurnKind.SUMMARY
                ? DialogueState.AWAITING_REVIEW : DialogueState.AWAITING_REPLY,revision,
                context.kind() == DialogueTurnKind.SUMMARY ? revisionId : null);
        nodes.saveAndFlush(DialogueNodeTransitions.node(target.node(),NodeRunStatus.WAITING_FOR_DIALOGUE,now,
                null,null,null));
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(final DialogueExecutionRequest request, final RuntimeException error) {
        final var claim = request.executionClaim();
        final var target = locks.lock(claim.workflowRunId(),claim.nodeRunId());
        final var snapshot = dialogues.lock(claim.nodeRunId());
        if (!current(target,snapshot,request)) return;
        leases.lockCurrent(claim.agentSessionClaim());
        final String code = error instanceof ForgeAgentException typed ? typed.code() : "AGENT_EXECUTOR_FAILED";
        final var failure = new NodeRunFailure(code,"Dialogue agent turn failed.");
        dialogues.finishTurn(claim.dialogueContext().dialogueTurnId(),DialogueTurnStatus.FAILED,null,code,failure.message());
        leases.finish(claim.agentSessionClaim(),AgentExecutionTurnStatus.FAILED,code,failure.message(),true);
        this.failed(target,snapshot,failure);
    }

    private void failed(final DialogueInvocationLock.Target target, final DialogueSnapshot snapshot, final NodeRunFailure failure) {
        dialogues.updateState(target.node().id(),DialogueState.FAILED,snapshot.revision()+1,null);
        nodes.saveAndFlush(DialogueNodeTransitions.node(target.node(),NodeRunStatus.FAILED,Instant.now(clock),null,failure,null));
        coordinator.reconcile(target.run());
    }

    private static boolean current(final DialogueInvocationLock.Target target, final DialogueSnapshot snapshot,
                                   final DialogueExecutionRequest request) {
        final var context = request.executionClaim().dialogueContext();
        return DialogueInvocationLock.activeWorkflow(target.run()) && target.node().status() == NodeRunStatus.RUNNING
                && snapshot.activeTurn() != null && context.dialogueTurnId().equals(snapshot.activeTurn().id())
                && request.executionClaim().agentSessionClaim().turnId().equals(snapshot.activeTurn().executionTurnId())
                && snapshot.activeTurn().status() == DialogueTurnStatus.RUNNING
                && snapshot.revision() == context.inputRevision()+1;
    }
}
