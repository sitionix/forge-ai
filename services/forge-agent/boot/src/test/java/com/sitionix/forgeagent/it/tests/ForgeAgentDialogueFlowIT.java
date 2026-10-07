package com.sitionix.forgeagent.it.tests;

import static com.sitionix.forgeagent.it.ForgeAgentFixtures.*;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.dialogue.*;
import com.sitionix.forgeagent.application.runtime.*;
import com.sitionix.forgeagent.application.usecase.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.model.dialogue.*;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Actual workers, sessions, PostgreSQL and routing; deterministic only at provider/workspace
 * boundaries.
 */
@IntegrationTest
class ForgeAgentDialogueFlowIT extends com.sitionix.forgeagent.it.infra.AgentManagementFixture {
  @Autowired ForgeAgentTestManager forgeIt;
  @Autowired WorkflowUseCases workflows;
  @Autowired WorkflowRunUseCases runs;
  @Autowired NodeRunWorker worker;
  @Autowired NodeRunCompletionWorker completion;
  @Autowired NodeRunRepository nodes;
  @Autowired AgentExecutionSessionRepository sessions;
  @Autowired AgentSessionLeaseService leases;
  @Autowired DialogueCommands dialogue;
  @Autowired ObjectMapper json;
  @MockBean AgentExecutor executor;
  @MockBean ExecutionWorkspaceResolver workspace;
  private static final String REPLY =
      "{\"message\":\"Clarified\",\"draft\":{\"summary\":\"Exact accepted"
          + " task\"},\"questions\":[],\"decisions\":[],\"sources\":[],\"readyForReview\":true}";

  @ParameterizedTest
  @EnumSource(
      value = DialogueOutputDisposition.class,
      names = {"ACCEPT", "DEFER"})
  void twoChatsAndCurrentSummaryDeliverExactAcceptedEnvelopeOnce(
      DialogueOutputDisposition disposition) throws Exception {
    Fixture f = fixture();
    NodeRun invocation = waiting(f, 1);
    var initial = dialogue.get(f.runId, invocation.id());
    assertThat(initial.state()).isEqualTo(DialogueState.AWAITING_REPLY);
    for (String text : List.of("First clarification\nwith lines", "Second clarification 😀")) {
      var state = dialogue.get(f.runId, invocation.id());
      UUID request = UUID.randomUUID();
      dialogue.send(f.runId, invocation.id(), request, state.revision(), text);
      dialogue.send(f.runId, invocation.id(), request, state.revision(), text);
      worker.poll();
      awaitReply(f, invocation);
    }
    var summary = summarize(f, invocation);
    assertThat(summary.turnCount()).isEqualTo(4);
    assertThat(nodes.findByWorkflowRunId(f.runId)).hasSize(2);
    UUID request = UUID.randomUUID();
    UUID port =
        f.dialogue.outputs().stream()
            .filter(p -> p.dialogueDisposition() == disposition)
            .findFirst()
            .orElseThrow()
            .id();
    dialogue.complete(
        f.runId, invocation.id(), request, summary.revision(), summary.summaryRevisionId(), port);
    assertThat(nodes.findById(invocation.id()).orElseThrow().routingCompletedAt()).isNull();
    completion.poll();
    completion.poll();
    worker.poll();
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(runs.getWorkflowRun(f.runId).status())
                    .isEqualTo(WorkflowRunStatus.SUCCEEDED));
    dialogue.complete(
        f.runId, invocation.id(), request, summary.revision(), summary.summaryRevisionId(), port);
    completion.poll();
    assertThat(nodes.findByWorkflowRunId(f.runId)).hasSize(3);
    var claims = ArgumentCaptor.forClass(NodeExecutionClaim.class);
    verify(executor, times(2)).execute(claims.capture());
    var reviewer =
        claims.getAllValues().stream()
            .filter(c -> c.sourceAgentId().equals(AGENT_B_ID))
            .findFirst()
            .orElseThrow();
    var accepted = nodes.findById(invocation.id()).orElseThrow().output().jsonValue();
    assertThat(reviewer.inputEnvelope().contributions())
        .singleElement()
        .satisfies(
            input -> {
              assertThat(input.sourceNodeRunId()).isEqualTo(invocation.id());
              assertThat(input.payload().jsonValue()).isEqualTo(accepted);
            });
    assertThat(json.readTree(accepted).path("dialogue").path("summaryRevisionId").asText())
        .isEqualTo(summary.summaryRevisionId().toString());
    assertThat(dialogue.messages(f.runId, invocation.id(), 0, 100).messages()).hasSize(6);
    var turns = ArgumentCaptor.forClass(DialogueExecutionRequest.class);
    verify(executor, times(4)).executeDialogue(turns.capture());
    assertThat(
            turns.getAllValues().stream()
                .map(r -> r.executionClaim().agentSessionClaim().sessionId())
                .distinct())
        .hasSize(1);
    assertThat(
            turns.getAllValues().stream()
                .map(r -> r.executionClaim().agentSessionClaim().turnId())
                .distinct())
        .hasSize(4);
  }

  @Test
  void reworkLoopAllocatesIndependentInvocationAndOldCompletionCannotLoopAgain() {
    Fixture f = fixture();
    NodeRun first = waiting(f, 1);
    var summary = summarize(f, first);
    UUID request = UUID.randomUUID(), rework = f.dialogue.outputs().get(1).id();
    dialogue.complete(
        f.runId, first.id(), request, summary.revision(), summary.summaryRevisionId(), rework);
    completion.poll();
    NodeRun second = waiting(f, 2);
    assertThat(second.id()).isNotEqualTo(first.id());
    var state = dialogue.get(f.runId, second.id());
    assertThat(state.turnCount()).isEqualTo(1);
    assertThat(dialogue.messages(f.runId, second.id(), 0, 100).messages()).hasSize(1);
    var contexts = sessions.findByWorkflowRunId(f.runId);
    UUID oldSession =
        contexts.stream()
            .filter(c -> first.id().equals(c.turn().nodeRunId()))
            .findFirst()
            .orElseThrow()
            .session()
            .id();
    UUID newSession =
        contexts.stream()
            .filter(c -> second.id().equals(c.turn().nodeRunId()))
            .findFirst()
            .orElseThrow()
            .session()
            .id();
    assertThat(newSession).isNotEqualTo(oldSession);
    dialogue.complete(
        f.runId, first.id(), request, summary.revision(), summary.summaryRevisionId(), rework);
    completion.poll();
    assertThat(nodes.findByWorkflowRunId(f.runId)).hasSize(4);
    assertThat(dialogue.get(f.runId, second.id()).revision()).isEqualTo(state.revision());
  }

  private Fixture fixture() {
    forgeIt
        .postgresql()
        .create()
        .to(PROJECT.withJson("project_alpha.json"))
        .to(AGENT_DEFINITION.withJson("agent_a.json"))
        .to(AGENT_DEFINITION.withJson("agent_b.json"))
        .build();
    when(workspace.resolve(any(), any()))
        .thenReturn(new ExecutionWorkspace(Path.of("/tmp"), List.of()));
    when(executor.execute(any()))
        .thenReturn(
            new AgentExecutionResult(new NodeRunOutput("{\"summary\":\"Boundary output\"}"), null));
    when(executor.executeDialogue(any()))
        .thenAnswer(
            invocation -> {
              DialogueExecutionRequest request = invocation.getArgument(0);
              var claim = request.executionClaim().agentSessionClaim();
              leases.persistConversation(claim, "conversation-" + claim.sessionId(), "fixture");
              leases.persistTurn(claim, "provider-turn-" + claim.turnId());
              return new AgentExecutionResult(new NodeRunOutput(REPLY), null);
            });
    Workflow w =
        workflows.createWorkflow(
            PROJECT_ALPHA_ID, new CreateWorkflowCommand("Upstream - Dialogue - Reviewer"));
    Node upstream = agent(AGENT_A_ID), reviewer = agent(AGENT_B_ID);
    Node d =
        new Node(
            UUID.randomUUID(),
            AGENT_A_ID,
            NodeInputMode.TASK_AND_DEPENDENCIES,
            List.of(new NodePort(UUID.randomUUID(), "Input", "Task", 0)),
            java.util.stream.IntStream.range(0, 3)
                .mapToObj(
                    i ->
                        new NodePort(
                            UUID.randomUUID(),
                            DialogueOutputDisposition.values()[i].name(),
                            "Outcome",
                            i,
                            DialogueOutputDisposition.values()[i]))
                .toList(),
            new NodePosition(300, 0),
            NodeScopeMode.GLOBAL,
            null,
            null,
            NodeType.DIALOGUE);
    workflows.updateWorkflow(
        w.id(),
        new SaveWorkflowCommand(
            w.name(),
            List.of(upstream, d, reviewer),
            List.of(
                edge(upstream.outputs().getFirst().id(), d.inputs().getFirst().id()),
                edge(d.outputs().get(0).id(), reviewer.inputs().getFirst().id()),
                edge(d.outputs().get(1).id(), upstream.inputs().getFirst().id()),
                edge(d.outputs().get(2).id(), reviewer.inputs().getFirst().id())),
            upstream.inputs().getFirst().id(),
            reviewer.outputs().getFirst().id()));
    return new Fixture(
        runs.createWorkflowRun(w.id(), new CreateWorkflowRunCommand("Groom precisely")).id(), d);
  }

  private NodeRun waiting(Fixture f, int count) {
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> {
              completion.poll();
              worker.poll();
              var invocations =
                  nodes.findByWorkflowRunId(f.runId).stream()
                      .filter(n -> n.sourceNodeId().equals(f.dialogue.id()))
                      .toList();
              assertThat(invocations).hasSize(count);
              assertThat(
                      invocations.stream()
                          .filter(n -> n.status() == NodeRunStatus.WAITING_FOR_DIALOGUE))
                  .hasSize(1);
            });
    return nodes.findByWorkflowRunId(f.runId).stream()
        .filter(
            n ->
                n.sourceNodeId().equals(f.dialogue.id())
                    && n.status() == NodeRunStatus.WAITING_FOR_DIALOGUE)
        .findFirst()
        .orElseThrow();
  }

  private void awaitReply(Fixture f, NodeRun node) {
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(dialogue.get(f.runId, node.id()).state())
                    .isEqualTo(DialogueState.AWAITING_REPLY));
  }

  private DialogueSnapshot summarize(Fixture f, NodeRun node) {
    var state = dialogue.get(f.runId, node.id());
    dialogue.summarize(f.runId, node.id(), UUID.randomUUID(), state.revision());
    worker.poll();
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(dialogue.get(f.runId, node.id()).state())
                    .isEqualTo(DialogueState.AWAITING_REVIEW));
    return dialogue.get(f.runId, node.id());
  }

  private Node agent(UUID id) {
    return new Node(
        UUID.randomUUID(),
        id,
        NodeInputMode.DEPENDENCIES_ONLY,
        List.of(new NodePort(UUID.randomUUID(), "Input", "Input", 0)),
        List.of(new NodePort(UUID.randomUUID(), "Output", "Output", 0)),
        new NodePosition(0, 0),
        NodeScopeMode.GLOBAL);
  }

  private WorkflowConnection edge(UUID from, UUID to) {
    return new WorkflowConnection(UUID.randomUUID(), from, to);
  }

  private record Fixture(UUID runId, Node dialogue) {}
}
