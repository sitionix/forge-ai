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
class ForgeAgentDialogueCommandsIT extends com.sitionix.forgeagent.it.infra.AgentManagementFixture {
    @Autowired com.sitionix.forgeagent.application.dialogue.DialogueCommands commands;
    @Autowired ForgeAgentTestManager forgeIt;
    @Autowired WorkflowUseCases workflows;
    @Autowired WorkflowRunUseCases runs;
    @Autowired DialogueRepository dialogues;
    @Autowired AgentExecutionSessionRepository sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.sitionix.forgeagent.domain.port.NodeRunRepository nodeRuns;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    @Test void initialIsQueuedExactlyOnceAndLostResponseRetryDoesNotDuplicate() {
        NodeRun node = createNode();
        commands.initialize(node.id());
        commands.initialize(node.id());
        assertThat(commands.get(node.workflowRunId(),node.id()).turnCount()).isEqualTo(1);
        waitForHuman(node);
        var before = commands.get(node.workflowRunId(),node.id());
        UUID request = UUID.randomUUID();
        var sent = commands.send(node.workflowRunId(),node.id(),request,before.revision(),"Clarification\nwith lines");
        var retry = commands.send(node.workflowRunId(),node.id(),request,before.revision(),"Clarification\nwith lines");
        assertThat(retry.revision()).isEqualTo(sent.revision());
        assertThat(retry.turnCount()).isEqualTo(2);
        assertThat(commands.messages(node.workflowRunId(),node.id(),0,100).messages()).singleElement()
                .extracting(DialogueMessage::text).isEqualTo("Clarification\nwith lines");
        assertThatThrownBy(() -> commands.send(node.workflowRunId(),node.id(),request,before.revision(),"Other"))
                .isInstanceOf(com.sitionix.forgeagent.domain.exception.ConflictException.class)
                .hasMessageContaining("request");
    }

    @Test void staleRevisionAndBusyTurnRejectNewCommandsAtomically() {
        NodeRun node = createNode();
        commands.initialize(node.id());
        waitForHuman(node);
        var snapshot = commands.get(node.workflowRunId(),node.id());
        assertThatThrownBy(() -> commands.send(node.workflowRunId(),node.id(),UUID.randomUUID(),snapshot.revision()-1,"Stale"))
                .isInstanceOf(com.sitionix.forgeagent.domain.exception.ConflictException.class);
        var summary = commands.summarize(node.workflowRunId(),node.id(),UUID.randomUUID(),snapshot.revision());
        assertThat(summary.activeTurn().kind()).isEqualTo(DialogueTurnKind.SUMMARY);
        assertThatThrownBy(() -> commands.send(node.workflowRunId(),node.id(),UUID.randomUUID(),summary.revision(),"Too soon"))
                .isInstanceOf(com.sitionix.forgeagent.domain.exception.ConflictException.class);
        assertThat(commands.messages(node.workflowRunId(),node.id(),0,100).messages()).isEmpty();
        assertThat(commands.get(node.workflowRunId(),node.id()).turnCount()).isEqualTo(2);
    }

    @Test void newMessageInvalidatesPreviousSummary() {
        NodeRun node = createNode();
        commands.initialize(node.id());
        waitForHuman(node);
        var snapshot = commands.get(node.workflowRunId(),node.id());
        var queuedSummary = commands.summarize(node.workflowRunId(),node.id(),UUID.randomUUID(),snapshot.revision());
        waitForHuman(node);
        snapshot = commands.get(node.workflowRunId(),node.id());
        final long waitingRevision = snapshot.revision();
        UUID revisionId = UUID.randomUUID();
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            var turn = queuedSummary.activeTurn().id();
            dialogues.appendRevision(new DialogueRevision(revisionId,node.id(),waitingRevision+1,turn,
                    DialogueTurnKind.SUMMARY, waitingRevision,"{}",Instant.now()));
            dialogues.updateState(node.id(),DialogueState.AWAITING_REVIEW,waitingRevision+1,revisionId);
        });
        var summary = commands.get(node.workflowRunId(),node.id());
        var sent = commands.send(node.workflowRunId(),node.id(),UUID.randomUUID(),summary.revision(),"Change requirement");
        assertThat(sent.summaryRevisionId()).isNull();
        assertThat(sent.latestRevision().id()).isEqualTo(revisionId);
    }

    @Test void concurrentTabsProduceOnlyOneMessageAndTurn() throws Exception {
        NodeRun node = createNode();
        commands.initialize(node.id());
        waitForHuman(node);
        long revision = commands.get(node.workflowRunId(),node.id()).revision();
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0,2).mapToObj(i -> executor.submit(() -> {
                barrier.await();
                try { commands.send(node.workflowRunId(),node.id(),UUID.randomUUID(),revision,"Tab " + i); return true; }
                catch (com.sitionix.forgeagent.domain.exception.ConflictException expected) { return false; }
            })).toList();
            assertThat(List.of(futures.get(0).get(),futures.get(1).get())).containsExactlyInAnyOrder(true,false);
        }
        assertThat(commands.messages(node.workflowRunId(),node.id(),0,100).messages()).hasSize(1);
        assertThat(commands.get(node.workflowRunId(),node.id()).turnCount()).isEqualTo(2);
    }

    private void waitForHuman(NodeRun node) {
        // Provider boundary is covered in Task 4; here finish the persisted INITIAL turn.
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            var snapshot = dialogues.lock(node.id());
            dialogues.finishTurn(snapshot.activeTurn().id(),DialogueTurnStatus.SUCCEEDED,"{}",null,null);
            jdbc.update("UPDATE agent_execution_turns SET status='SUCCEEDED' WHERE node_run_id=?",node.id());
            dialogues.updateState(node.id(),DialogueState.AWAITING_REPLY,snapshot.revision()+1,null);
            jdbc.update("UPDATE node_runs SET status='WAITING_FOR_DIALOGUE' WHERE id=?",node.id());
        });
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
