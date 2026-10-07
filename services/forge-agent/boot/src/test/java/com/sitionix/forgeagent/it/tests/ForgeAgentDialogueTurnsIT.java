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
class ForgeAgentDialogueTurnsIT extends com.sitionix.forgeagent.it.infra.AgentManagementFixture {
    @Autowired com.sitionix.forgeagent.application.dialogue.DialogueCommands commands;
    @Autowired com.sitionix.forgeagent.application.dialogue.DialogueTurnLifecycle lifecycle;
    @Autowired com.sitionix.forgeagent.application.runtime.AgentSessionLeaseService leases;
    @Autowired ForgeAgentTestManager forgeIt;
    @Autowired WorkflowUseCases workflows;
    @Autowired WorkflowRunUseCases runs;
    @Autowired DialogueRepository dialogues;
    @Autowired AgentExecutionSessionRepository sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.sitionix.forgeagent.domain.port.NodeRunRepository nodeRuns;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    private static final String REPLY = "{\"message\":\"Clarified task\",\"draft\":{\"summary\":\"Task\"},\"questions\":[],\"decisions\":[],\"sources\":[],\"readyForReview\":true}";

    @Test void initialChatAndSummaryReuseOneSessionAndWaitWithoutLease() {
        NodeRun node = createNode();
        commands.initialize(node.id());
        var initial = executeCurrent(node);
        var snapshot = commands.get(node.workflowRunId(),node.id());
        assertThat(snapshot.state()).isEqualTo(DialogueState.AWAITING_REPLY);
        assertThat(nodeRuns.findById(node.id()).orElseThrow().status()).isEqualTo(NodeRunStatus.WAITING_FOR_DIALOGUE);
        assertThat(runs.getWorkflowRun(node.workflowRunId()).status()).isEqualTo(WorkflowRunStatus.RUNNING);
        commands.send(node.workflowRunId(),node.id(),UUID.randomUUID(),snapshot.revision(),"User decision");
        var chat = executeCurrent(node);
        snapshot = commands.get(node.workflowRunId(),node.id());
        commands.summarize(node.workflowRunId(),node.id(),UUID.randomUUID(),snapshot.revision());
        var summary = executeCurrent(node);
        assertThat(List.of(chat.sessionId(),summary.sessionId())).containsOnly(initial.sessionId());
        assertThat(chat.providerConversationId()).isEqualTo("thread-" + initial.sessionId());
        assertThat(summary.providerConversationId()).isEqualTo(chat.providerConversationId());
        assertThat(List.of(initial.turnId(),chat.turnId(),summary.turnId())).doesNotHaveDuplicates();
        var finalSnapshot = commands.get(node.workflowRunId(),node.id());
        assertThat(finalSnapshot.state()).isEqualTo(DialogueState.AWAITING_REVIEW);
        assertThat(finalSnapshot.summaryRevisionId()).isEqualTo(finalSnapshot.latestRevision().id());
        assertThat(finalSnapshot.latestRevision().kind()).isEqualTo(DialogueTurnKind.SUMMARY);
        assertThat(finalSnapshot.activeTurn()).isNull();
        assertThat(commands.messages(node.workflowRunId(),node.id(),0,100).messages()).hasSize(4);
        assertThat(sessions.findSession(initial.sessionId()).orElseThrow()).satisfies(s -> {
            assertThat(s.leaseOwnerId()).isNull();
            assertThat(s.status()).isEqualTo(AgentExecutionSessionStatus.IDLE);
        });
        assertThat(runs.getWorkflowRun(node.workflowRunId()).nodeRuns()).hasSize(1);
    }

    @Test void invalidResponseCreatesNoAssistantMessageAndFailsExplicitly() {
        NodeRun node = createNode();
        commands.initialize(node.id());
        var snapshot = commands.get(node.workflowRunId(),node.id());
        var request = lifecycle.claim(snapshot.activeTurn().id()).orElseThrow();
        var invalid = new com.sitionix.forgeagent.application.runtime.AgentExecutionResult(new NodeRunOutput("{}"),null);
        assertThatThrownBy(() -> lifecycle.succeed(request,invalid)).isInstanceOf(com.sitionix.forgeagent.domain.exception.ValidationException.class);
        lifecycle.fail(request,new com.sitionix.forgeagent.domain.exception.ValidationException("INVALID_DIALOGUE_RESULT","Invalid"));
        assertThat(commands.get(node.workflowRunId(),node.id()).state()).isEqualTo(DialogueState.FAILED);
        assertThat(commands.messages(node.workflowRunId(),node.id(),0,100).messages()).isEmpty();
        assertThat(runs.getWorkflowRun(node.workflowRunId()).status()).isEqualTo(WorkflowRunStatus.FAILED);
    }

    @Test void expiredFencingTokenCannotAppendProviderReply() {
        NodeRun node = createNode();
        commands.initialize(node.id());
        var request = lifecycle.claim(commands.get(node.workflowRunId(),node.id()).activeTurn().id()).orElseThrow();
        jdbc.update("UPDATE agent_execution_sessions SET lease_token=lease_token+1 WHERE id=?",request.executionClaim().agentSessionClaim().sessionId());
        assertThatThrownBy(() -> lifecycle.succeed(request,new com.sitionix.forgeagent.application.runtime.AgentExecutionResult(new NodeRunOutput(REPLY),null)))
                .isInstanceOf(com.sitionix.forgeagent.domain.exception.ConflictException.class);
        assertThat(commands.messages(node.workflowRunId(),node.id(),0,100).messages()).isEmpty();
    }

    private AgentSessionExecutionClaim executeCurrent(NodeRun node) {
        var snapshot = commands.get(node.workflowRunId(),node.id());
        var request = lifecycle.claim(snapshot.activeTurn().id()).orElseThrow();
        var claim = request.executionClaim().agentSessionClaim();
        leases.persistConversation(claim,"thread-" + claim.sessionId(),"fixture-version");
        leases.persistTurn(claim,"provider-" + claim.turnId());
        assertThat(lifecycle.succeed(request,new com.sitionix.forgeagent.application.runtime.AgentExecutionResult(new NodeRunOutput(REPLY),null))).isTrue();
        assertThat(lifecycle.succeed(request,new com.sitionix.forgeagent.application.runtime.AgentExecutionResult(new NodeRunOutput(REPLY),null))).isFalse();
        return claim;
    }

    private NodeRun createNode() { return createNode(NodeType.DIALOGUE); }

    private NodeRun createNode(NodeType type) {
        forgeIt.postgresql().create().to(PROJECT.withJson("project_alpha.json")).build();
        forgeIt.postgresql().create().to(AGENT_DEFINITION.withJson("agent_a.json")).build();
        Workflow w = workflows.createWorkflow(PROJECT_ALPHA_ID, new CreateWorkflowCommand("Dialogue storage"));
        NodePort input = new NodePort(UUID.randomUUID(), "Input", "Task context", 0);
        NodePort output = new NodePort(UUID.randomUUID(), "Accept", "Reviewed result", 0, type == NodeType.DIALOGUE ? DialogueOutputDisposition.ACCEPT : null);
        Node n = new Node(UUID.randomUUID(), AGENT_A_ID, NodeInputMode.TASK_AND_DEPENDENCIES,
                List.of(input), List.of(output), new NodePosition(0, 0), NodeScopeMode.GLOBAL,
                null, null, type);
        workflows.updateWorkflow(w.id(), new SaveWorkflowCommand(w.name(), List.of(n), List.of(), input.id(), output.id()));
        return runs.getWorkflowRun(runs.createWorkflowRun(w.id(), new CreateWorkflowRunCommand("Task")).id())
                .nodeRuns().getFirst();
    }
}
