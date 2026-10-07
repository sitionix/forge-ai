package com.sitionix.forgeagent.it.tests;

import com.sitionix.forgeagent.application.usecase.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.model.dialogue.*;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import com.sitionix.forgeagent.domain.port.dialogue.DialogueRepository;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.*;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.*;
import static org.assertj.core.api.Assertions.*;

@IntegrationTest
class ForgeAgentDialogueLifecycleIT extends com.sitionix.forgeagent.it.infra.AgentManagementFixture {
    @Autowired com.sitionix.forgeagent.application.dialogue.DialogueCommands commands;
    @Autowired com.sitionix.forgeagent.application.dialogue.DialogueTurnLifecycle lifecycle;
    @Autowired com.sitionix.forgeagent.application.runtime.AgentSessionLeaseService leases;
    @Autowired com.sitionix.forgeagent.application.usecase.CancelWorkflowRunUseCase cancellation;
    @Autowired com.sitionix.forgeagent.application.runtime.NodeRunCompletionProcessor completionProcessor;
    @Autowired com.sitionix.forgeagent.application.runtime.AgentExecutionRecoveryService recovery;
    @Autowired com.sitionix.forgeagent.application.usecase.ResetAgentExecutionContextUseCase reset;
    @org.springframework.boot.test.mock.mockito.MockBean com.sitionix.forgeagent.application.runtime.AgentExecutor executor;
    @Autowired ForgeAgentTestManager forgeIt;
    @Autowired WorkflowUseCases workflows;
    @Autowired WorkflowRunUseCases runs;
    @Autowired DialogueRepository dialogues;
    @Autowired AgentExecutionSessionRepository sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.sitionix.forgeagent.domain.port.NodeRunRepository nodeRuns;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    private static final String REPLY = "{\"message\":\"Clarified task\",\"draft\":{\"summary\":\"Task\"},\"questions\":[],\"decisions\":[],\"sources\":[],\"readyForReview\":true}";

    @Test void exactCompletionIsIdempotentAndRoutingResumesAfterCommit() {
        NodeRun node = createNode();
        commands.initialize(node.id());
        executeCurrent(node);
        var waiting = commands.get(node.workflowRunId(),node.id());
        commands.summarize(node.workflowRunId(),node.id(),UUID.randomUUID(),waiting.revision());
        var lease = executeCurrent(node);
        var summary = commands.get(node.workflowRunId(),node.id());
        UUID output = runs.getWorkflowRun(node.workflowRunId()).runtimeGraph().ports().stream()
                .filter(p -> p.dialogueDisposition() == DialogueOutputDisposition.ACCEPT).findFirst().orElseThrow().sourcePortId();
        UUID request = UUID.randomUUID();
        var completed = commands.complete(node.workflowRunId(),node.id(),request,summary.revision(),summary.summaryRevisionId(),output);
        assertThat(completed.state()).isEqualTo(DialogueState.COMPLETED);
        assertThat(sessions.findSession(lease.sessionId()).orElseThrow().status()).isEqualTo(AgentExecutionSessionStatus.CLOSED);
        assertThat(nodeRuns.findById(node.id()).orElseThrow().routingCompletedAt()).isNull();
        completionProcessor.process(node.id());
        completionProcessor.process(node.id());
        assertThat(runs.getWorkflowRun(node.workflowRunId()).status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
        assertThat(commands.complete(node.workflowRunId(),node.id(),request,summary.revision(),summary.summaryRevisionId(),output).revision())
                .isEqualTo(completed.revision());
        assertThat(nodeRuns.findById(node.id()).orElseThrow().output().jsonValue())
                .contains("contractVersion","result","Task");
    }

    @Test void cancellingHumanWaitClosesSessionAndKeepsTranscriptReadOnly() {
        NodeRun node = createNode();
        commands.initialize(node.id());
        var lease = executeCurrent(node);
        assertThat(nodeRuns.existsActiveBySourceAgentId(node.sourceAgentId())).isTrue();
        cancellation.execute(node.workflowRunId());
        assertThat(commands.get(node.workflowRunId(),node.id()).state()).isEqualTo(DialogueState.CANCELLED);
        assertThat(sessions.findSession(lease.sessionId()).orElseThrow().status()).isEqualTo(AgentExecutionSessionStatus.CLOSED);
        assertThat(commands.messages(node.workflowRunId(),node.id(),0,100).messages()).hasSize(1);
    }

    @Test void queuedTurnCanBeCancelledBeforeProviderHandleExists() {
        NodeRun node = createNode();
        commands.initialize(node.id());
        cancellation.execute(node.workflowRunId());
        assertThat(commands.get(node.workflowRunId(),node.id()).state()).isEqualTo(DialogueState.CANCELLED);
        assertThat(dialogues.queuedTurnIds()).isEmpty();
    }

    @Test void expiredActiveTurnFailsWithoutHiddenRedispatchAndLateReplyIsIgnored() {
        NodeRun node = createNode();
        commands.initialize(node.id());
        var request = lifecycle.claim(commands.get(node.workflowRunId(),node.id()).activeTurn().id()).orElseThrow();
        jdbc.update("UPDATE agent_execution_sessions SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",request.executionClaim().agentSessionClaim().sessionId());
        assertThat(recovery.reconcileExpired()).isEqualTo(1);
        assertThat(commands.get(node.workflowRunId(),node.id()).state()).isEqualTo(DialogueState.FAILED);
        assertThat(dialogues.queuedTurnIds()).isEmpty();
        assertThat(lifecycle.succeed(request,new com.sitionix.forgeagent.application.runtime.AgentExecutionResult(new NodeRunOutput(REPLY),null))).isFalse();
        assertThat(commands.messages(node.workflowRunId(),node.id(),0,100).messages()).isEmpty();
    }

    @Test void openDialogueContextCannotBeReset() {
        NodeRun node = createNode();
        commands.initialize(node.id());
        var lease = executeCurrent(node);
        assertThatThrownBy(() -> reset.execute(lease.sessionId())).isInstanceOf(com.sitionix.forgeagent.domain.exception.ConflictException.class);
        assertThat(sessions.findSession(lease.sessionId()).orElseThrow().contextResetAt()).isNull();
    }

    @Test void clarificationAfterSummaryRejectsAcceptanceFromOldTab() {
        NodeRun node = createNode();
        commands.initialize(node.id());
        executeCurrent(node);
        var waiting = commands.get(node.workflowRunId(),node.id());
        commands.summarize(node.workflowRunId(),node.id(),UUID.randomUUID(),waiting.revision());
        executeCurrent(node);
        var oldSummary = commands.get(node.workflowRunId(),node.id());
        commands.send(node.workflowRunId(),node.id(),UUID.randomUUID(),oldSummary.revision(),"New requirement");
        executeCurrent(node);
        var current = commands.get(node.workflowRunId(),node.id());
        UUID port = acceptPort(node);
        assertThatThrownBy(() -> commands.complete(node.workflowRunId(),node.id(),UUID.randomUUID(),oldSummary.revision(),oldSummary.summaryRevisionId(),port))
                .isInstanceOf(com.sitionix.forgeagent.domain.exception.ConflictException.class);
        assertThatThrownBy(() -> commands.complete(node.workflowRunId(),node.id(),UUID.randomUUID(),current.revision(),oldSummary.summaryRevisionId(),port))
                .isInstanceOf(com.sitionix.forgeagent.domain.exception.ConflictException.class);
        assertThat(commands.get(node.workflowRunId(),node.id()).completion()).isNull();
    }

    @Test void sendAndCompleteFromDifferentTabsHaveOnlyOneWinner() throws Exception {
        NodeRun node = createNode();
        commands.initialize(node.id());
        executeCurrent(node);
        var waiting = commands.get(node.workflowRunId(),node.id());
        commands.summarize(node.workflowRunId(),node.id(),UUID.randomUUID(),waiting.revision());
        executeCurrent(node);
        var summary = commands.get(node.workflowRunId(),node.id());
        UUID port = acceptPort(node);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var send = pool.submit(() -> {
                barrier.await();
                try { commands.send(node.workflowRunId(),node.id(),UUID.randomUUID(),summary.revision(),"Change"); return true; }
                catch (com.sitionix.forgeagent.domain.exception.ConflictException expected) { return false; }
            });
            var complete = pool.submit(() -> {
                barrier.await();
                try { commands.complete(node.workflowRunId(),node.id(),UUID.randomUUID(),summary.revision(),summary.summaryRevisionId(),port); return true; }
                catch (com.sitionix.forgeagent.domain.exception.ConflictException expected) { return false; }
            });
            assertThat(List.of(send.get(),complete.get())).containsExactlyInAnyOrder(true,false);
        }
        var current = commands.get(node.workflowRunId(),node.id());
        assertThat(current.state()).isIn(DialogueState.RUNNING,DialogueState.COMPLETED);
        if (current.completion()!=null) assertThat(current.activeTurn()).isNull();
        else assertThat(current.activeTurn().kind()).isEqualTo(DialogueTurnKind.CHAT);
    }

    private UUID acceptPort(NodeRun node) {
        return runs.getWorkflowRun(node.workflowRunId()).runtimeGraph().ports().stream()
                .filter(p -> p.dialogueDisposition()==DialogueOutputDisposition.ACCEPT).findFirst().orElseThrow().sourcePortId();
    }

    @Test void activeCancellationFencesLateReplyAfterProviderInterruption() {
        NodeRun node = createNode();
        commands.initialize(node.id());
        var request = lifecycle.claim(commands.get(node.workflowRunId(),node.id()).activeTurn().id()).orElseThrow();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.when(executor.secureCancellation(node.id())).thenReturn(java.util.Optional.of(() -> interrupted.set(true)));
        cancellation.execute(node.workflowRunId());
        assertThat(interrupted).isTrue();
        assertThat(commands.get(node.workflowRunId(),node.id()).state()).isEqualTo(DialogueState.CANCELLED);
        assertThat(lifecycle.succeed(request,new com.sitionix.forgeagent.application.runtime.AgentExecutionResult(new NodeRunOutput(REPLY),null))).isFalse();
        assertThat(commands.messages(node.workflowRunId(),node.id(),0,100).messages()).isEmpty();
    }

    @Test void reworkAllowsCurrentSummaryWithBlockingQuestions() { completeBlocked(DialogueOutputDisposition.REWORK); }
    @Test void deferAllowsCurrentSummaryWithBlockingQuestions() { completeBlocked(DialogueOutputDisposition.DEFER); }

    private void completeBlocked(DialogueOutputDisposition disposition) {
        NodeRun node = createNode();
        commands.initialize(node.id());
        executeCurrent(node);
        var before = commands.get(node.workflowRunId(),node.id());
        commands.summarize(node.workflowRunId(),node.id(),UUID.randomUUID(),before.revision());
        var blocked = REPLY.replace("\"readyForReview\":true","\"readyForReview\":false")
                .replace("\"questions\":[]","\"questions\":[{\"id\":\"q1\",\"text\":\"Which?\",\"reason\":\"Scope\",\"blocking\":true,\"recommendation\":null}]");
        executeCurrent(node,blocked);
        var summary = commands.get(node.workflowRunId(),node.id());
        var output = runs.getWorkflowRun(node.workflowRunId()).runtimeGraph().ports().stream()
                .filter(p -> p.dialogueDisposition() == disposition).findFirst().orElseThrow().sourcePortId();
        var completed = commands.complete(node.workflowRunId(),node.id(),UUID.randomUUID(),summary.revision(),summary.summaryRevisionId(),output);
        assertThat(completed.completion().disposition()).isEqualTo(disposition);
        assertThat(nodeRuns.findById(node.id()).orElseThrow().output().jsonValue()).contains("Which?");
    }

    private AgentSessionExecutionClaim executeCurrent(NodeRun node) { return executeCurrent(node,REPLY); }

    private AgentSessionExecutionClaim executeCurrent(NodeRun node,String reply) {
        var snapshot = commands.get(node.workflowRunId(),node.id());
        var request = lifecycle.claim(snapshot.activeTurn().id()).orElseThrow();
        var claim = request.executionClaim().agentSessionClaim();
        leases.persistConversation(claim,"thread-" + claim.sessionId(),"fixture-version");
        leases.persistTurn(claim,"provider-" + claim.turnId());
        assertThat(lifecycle.succeed(request,new com.sitionix.forgeagent.application.runtime.AgentExecutionResult(new NodeRunOutput(reply),null))).isTrue();
        assertThat(lifecycle.succeed(request,new com.sitionix.forgeagent.application.runtime.AgentExecutionResult(new NodeRunOutput(reply),null))).isFalse();
        return claim;
    }

    private NodeRun createNode() { return createNode(NodeType.DIALOGUE); }

    private NodeRun createNode(NodeType type) {
        forgeIt.postgresql().create().to(PROJECT.withJson("project_alpha.json")).build();
        forgeIt.postgresql().create().to(AGENT_DEFINITION.withJson("agent_a.json")).build();
        Workflow w = workflows.createWorkflow(PROJECT_ALPHA_ID, new CreateWorkflowCommand("Dialogue storage"));
        NodePort input = new NodePort(UUID.randomUUID(), "Input", "Task context", 0);
        NodePort output = new NodePort(UUID.randomUUID(), "Accept", "Reviewed result", 0, type == NodeType.DIALOGUE ? DialogueOutputDisposition.ACCEPT : null);
        var outputs = type == NodeType.DIALOGUE ? List.of(output,
                new NodePort(UUID.randomUUID(),"Rework","Return for rework",1,DialogueOutputDisposition.REWORK),
                new NodePort(UUID.randomUUID(),"Defer","Postpone",2,DialogueOutputDisposition.DEFER)) : List.of(output);
        Node n = new Node(UUID.randomUUID(), AGENT_A_ID, NodeInputMode.TASK_AND_DEPENDENCIES,
                List.of(input), outputs, new NodePosition(0, 0), NodeScopeMode.GLOBAL,
                null, null, type);
        workflows.updateWorkflow(w.id(), new SaveWorkflowCommand(w.name(), List.of(n), List.of(), input.id(), output.id()));
        return runs.getWorkflowRun(runs.createWorkflowRun(w.id(), new CreateWorkflowRunCommand("Task")).id())
                .nodeRuns().getFirst();
    }
}
