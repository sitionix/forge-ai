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
class ForgeAgentDialogueStorageIT extends com.sitionix.forgeagent.it.infra.AgentManagementFixture {
    @Autowired ForgeAgentTestManager forgeIt;
    @Autowired WorkflowUseCases workflows;
    @Autowired WorkflowRunUseCases runs;
    @Autowired DialogueRepository dialogues;
    @Autowired AgentExecutionSessionRepository sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.sitionix.forgeagent.domain.port.NodeRunRepository nodeRuns;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    @Test void multipleDialogueTurnsShareOneInvocationSession() {
        NodeRun node = createNode();
        var tx = new TransactionTemplate(txManager);
        var first = queued(node, DialogueTurnKind.INITIAL, 0);
        var initial = tx.execute(status -> {
            dialogues.create(node.id());
            dialogues.insertTurn(first);
            return sessions.allocateDialogueTurn(node, first.id(), "codex");
        });
        assertThat(sessions.findByNodeRunId(node.id())).isEmpty();
        assertThat(sessions.findByExecutionTurnId(initial.turn().id())).contains(initial);
        assertThat(sessions.acquire(node.id(), "ordinary-worker")).isEmpty();
        var claim = tx.execute(status -> sessions.acquireTurn(initial.turn().id(), "dialogue-worker").orElseThrow());
        tx.executeWithoutResult(status -> {
            assertThat(sessions.finish(claim.sessionId(), claim.turnId(), claim.leaseOwnerId(), claim.leaseToken(),
                    AgentExecutionTurnStatus.SUCCEEDED, null, null, false)).isTrue();
            dialogues.finishTurn(first.id(), DialogueTurnStatus.SUCCEEDED, "{}", null, null);
        });
        var chat = tx.execute(status -> {
            var second = queued(node, DialogueTurnKind.SUMMARY, 1);
            dialogues.insertTurn(second);
            return sessions.allocateDialogueTurn(node, second.id(), "codex");
        });
        assertThat(chat.session().id()).isEqualTo(initial.session().id());
        assertThat(chat.turn().sequence()).isEqualTo(2);
        assertThat(chat.turn().id()).isNotEqualTo(initial.turn().id());
        assertThat(sessions.findSession(initial.session().id()).orElseThrow().leaseOwnerId()).isNull();
    }

    @Test void transcriptIsAppendOnlyAndOwnerBindingIsEnforced() {
        NodeRun node = createNode();
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            dialogues.create(node.id());
            var turn = queued(node, DialogueTurnKind.INITIAL, 0);
            dialogues.insertTurn(turn);
            sessions.allocateDialogueTurn(node, turn.id(), "codex");
            dialogues.appendMessage(new DialogueMessage(UUID.randomUUID(), node.id(), 1, DialogueMessageRole.USER,
                    "First\nmessage", null, Instant.now()));
        });
        assertThat(dialogues.messages(node.id(), 0, 10)).singleElement()
                .extracting(DialogueMessage::text).isEqualTo("First\nmessage");
        assertThatThrownBy(() -> jdbc.update("UPDATE dialogue_messages SET text='changed' WHERE node_run_id=?", node.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM dialogue_messages WHERE node_run_id=?", node.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE agent_execution_turns SET dialogue_turn_id=NULL WHERE node_run_id=?", node.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void ordinaryTurnRemainsUnique() {
        NodeRun node = createNode(NodeType.AGENT);
        assertThat(sessions.findByNodeRunId(node.id())).isPresent();
        assertThatThrownBy(() -> new TransactionTemplate(txManager).execute(status -> sessions.allocate(node, "codex")))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_execution_turns WHERE node_run_id=?", Integer.class, node.id()))
                .isEqualTo(1);
    }

    @Test void dialogueBindingRejectsWrongOwner() {
        NodeRun node = createNode();
        var tx = new TransactionTemplate(txManager);
        var turn = queued(node, DialogueTurnKind.INITIAL, 0);
        tx.executeWithoutResult(status -> { dialogues.create(node.id()); dialogues.insertTurn(turn); });
        assertThatThrownBy(() -> tx.execute(status -> sessions.allocateDialogueTurn(node, UUID.randomUUID(), "codex")))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_execution_sessions WHERE dialogue_node_run_id=?", Integer.class, node.id()))
                .isZero();
    }

    @Test void reactivatedSourceNodeHasIndependentConversation() {
        NodeRun first = createNode();
        NodeRun next = new NodeRun(UUID.randomUUID(), first.workflowRunId(), first.sourceNodeId(), first.sourceAgentId(),
                first.agentName(), first.agentInstructions(), first.agentOutputSchema(), first.inputMode(), first.position(),
                first.executionFrameId(), first.enteredViaInputPortId(), first.activationFrameId(), null, null,
                NodeRunStatus.PENDING, null, null, first.executionModel(), Instant.now(), null, null, null,
                first.contextMode(), null, first.id(), null, null, NodeType.DIALOGUE);
        var tx = new TransactionTemplate(txManager);
        var initial = tx.execute(status -> {
            dialogues.create(first.id());
            var turn = queued(first, DialogueTurnKind.INITIAL, 0);
            dialogues.insertTurn(turn);
            return sessions.allocateDialogueTurn(first, turn.id(), "codex");
        });
        var later = tx.execute(status -> {
            nodeRuns.saveAndFlush(next);
            dialogues.create(next.id());
            var turn = queued(next, DialogueTurnKind.INITIAL, 0);
            dialogues.insertTurn(turn);
            return sessions.allocateDialogueTurn(next, turn.id(), "codex");
        });
        assertThat(later.session().id()).isNotEqualTo(initial.session().id());
        assertThat(later.turn().sequence()).isEqualTo(1);
        assertThat(dialogues.find(first.id())).isPresent();
        assertThat(dialogues.find(next.id())).isPresent();
    }

    private DialogueTurn queued(NodeRun node, DialogueTurnKind kind, long revision) {
        return new DialogueTurn(UUID.randomUUID(), node.id(), kind, kind==DialogueTurnKind.INITIAL ? null : UUID.randomUUID(), null, revision,
                DialogueTurnStatus.QUEUED, null, null, null, null, Instant.now(), null);
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
