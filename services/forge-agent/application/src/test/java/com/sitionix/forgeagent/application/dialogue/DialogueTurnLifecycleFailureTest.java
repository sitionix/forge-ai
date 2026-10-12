package com.sitionix.forgeagent.application.dialogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.sitionix.forgeagent.application.runtime.*;
import com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.model.dialogue.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.domain.port.dialogue.DialogueRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class DialogueTurnLifecycleFailureTest {
    @ParameterizedTest
    @ValueSource(strings = {"badRequest", "LLM_BROWSER_DENIED", "INVALID_DIALOGUE_RESULT", "AGENT_EXECUTOR_FAILED"})
    void safeReasonAndExistingCodeSurviveDialogueSessionAndNodeBoundaries(String code) {
        var dialogues = mock(DialogueRepository.class);
        var nodes = mock(NodeRunRepository.class);
        var leases = mock(AgentSessionLeaseService.class);
        var locks = mock(DialogueInvocationLock.class);
        var coordinator = mock(WorkflowExecutionCoordinator.class);
        UUID runId = UUID.randomUUID(), nodeId = UUID.randomUUID(), dialogueId = UUID.randomUUID();
        var node = new NodeRun(nodeId, runId, UUID.randomUUID(), UUID.randomUUID(), "Dialogue", "Instructions",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"), NodeInputMode.DEPENDENCIES_ONLY,
                new NodePosition(0, 0), UUID.randomUUID(), null, null, null, null, NodeRunStatus.RUNNING,
                null, null, new NodeRunExecutionModel("codex", "model", null), Instant.EPOCH, Instant.EPOCH,
                null, null, NodeContextMode.DIALOGUE_WITHIN_NODE_RUN, 1, null, null, null, NodeType.DIALOGUE);
        var run = new WorkflowRun(runId, UUID.randomUUID(), UUID.randomUUID(), null, "Flow", null,
                WorkflowRunStatus.RUNNING, List.of(node), List.of(), List.of(), null, null, null,
                Instant.EPOCH, Instant.EPOCH, null, List.of());
        var session = new AgentSessionExecutionClaim(UUID.randomUUID(), UUID.randomUUID(), nodeId,
                "test-owner", 1, Instant.MAX, "thread-1", "codex", NodeContextMode.DIALOGUE_WITHIN_NODE_RUN);
        var context = new DialogueExecutionContext(dialogueId, DialogueTurnKind.CHAT, 0, "{}");
        var request = new DialogueExecutionRequest(new NodeExecutionClaim(runId, nodeId, UUID.randomUUID(),
                null, "Dialogue", "Instructions", AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                new NodeRunExecutionModel("codex", "model", null), null, List.of(), null, session, context));
        var turn = new DialogueTurn(dialogueId, nodeId, DialogueTurnKind.CHAT, UUID.randomUUID(), null,
                0, DialogueTurnStatus.RUNNING, session.turnId(), null, null, null, Instant.EPOCH, null);
        var snapshot = new DialogueSnapshot(nodeId, DialogueState.RUNNING, 1, null, null, turn,
                null, 0, 1, Instant.EPOCH, Instant.EPOCH);
        when(locks.lock(runId, nodeId)).thenReturn(new DialogueInvocationLock.Target(run, node));
        when(dialogues.lock(nodeId)).thenReturn(snapshot);
        var lifecycle = new DialogueTurnLifecycle(dialogues, nodes, mock(AgentExecutionSessionRepository.class),
                locks, leases, mock(ExecutionWorkspaceResolver.class), mock(DialogueExecutionRequestFactory.class),
                mock(DialogueTurnResultPolicy.class), mock(DialogueTranscript.class), coordinator, Clock.systemUTC());
        RuntimeException failure = code.equals("AGENT_EXECUTOR_FAILED")
                ? new IllegalStateException("Unknown provider failure TOKEN=fixture-secret")
                : new InfrastructureExecutionException(code, "Request rejected TOKEN=fixture-secret");
        String message = code.equals("AGENT_EXECUTOR_FAILED")
                ? "Unknown provider failure TOKEN=[REDACTED]" : "Request rejected TOKEN=[REDACTED]";

        lifecycle.fail(request, failure);

        verify(dialogues).finishTurn(dialogueId, DialogueTurnStatus.FAILED, null, code, message);
        verify(leases).finish(session, AgentExecutionTurnStatus.FAILED, code, message, true);
        var saved = ArgumentCaptor.forClass(NodeRun.class);
        verify(nodes).saveAndFlush(saved.capture());
        assertThat(saved.getValue().failure()).isEqualTo(new NodeRunFailure(code, message));
        assertThat(saved.getValue().status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(saved.getValue().output()).isNull();
        verify(coordinator).reconcile(run);

        clearInvocations(dialogues, nodes, leases, coordinator);
        when(dialogues.lock(nodeId)).thenReturn(new DialogueSnapshot(nodeId, DialogueState.RUNNING, 2,
                null, null, turn, null, 0, 1, Instant.EPOCH, Instant.EPOCH));
        lifecycle.fail(request, failure);
        verify(leases, never()).finish(any(), any(), any(), any(), anyBoolean());
        verify(nodes, never()).saveAndFlush(any());
        verify(coordinator, never()).reconcile(any(WorkflowRun.class));
    }
}
