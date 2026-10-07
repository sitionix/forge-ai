package com.sitionix.forgeagent.it.tests;

import static org.assertj.core.api.Assertions.*;

import com.sitionix.forgeagent.ForgeAgentApplication;
import com.sitionix.forgeagent.application.dialogue.*;
import com.sitionix.forgeagent.application.runtime.*;
import com.sitionix.forgeagent.application.usecase.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.model.dialogue.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** Recreates all application beans and connections against one persisted database. */
class ForgeAgentDialogueRestartIT extends com.sitionix.forgeagent.it.infra.AgentManagementFixture {
  private static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:16-alpine");
  private static final String REPLY =
      "{\"message\":\"Ready\",\"draft\":{\"summary\":\"Restart-safe"
          + " task\"},\"questions\":[],\"decisions\":[],\"sources\":[],\"readyForReview\":true}";

  @BeforeAll
  static void startDatabase() throws Exception {
    DATABASE.start();
    write("dialogue-restart-database", DATABASE.getPassword());
  }

  @AfterAll
  static void stopDatabase() {
    DATABASE.stop();
  }

  @ParameterizedTest
  @ValueSource(strings = {"WAITING", "QUEUED_CHAT", "ACTIVE", "UNROUTED"})
  void restartPreservesHistoryAndNeverReplaysUncertainDispatch(String stage) {
    Fixture f;
    DialogueSnapshot before;
    DialogueExecutionRequest active = null;
    try (var original = startApplication()) {
      f = fixture(original);
      var commands = original.getBean(DialogueCommands.class);
      commands.initialize(f.nodeId);
      execute(original, f);
      if (stage.equals("QUEUED_CHAT") || stage.equals("ACTIVE")) {
        commands.send(
            f.runId,
            f.nodeId,
            UUID.randomUUID(),
            commands.get(f.runId, f.nodeId).revision(),
            "Pending clarification");
      }
      if (stage.equals("ACTIVE")) {
        active =
            original
                .getBean(DialogueTurnLifecycle.class)
                .claim(commands.get(f.runId, f.nodeId).activeTurn().id())
                .orElseThrow();
        original
            .getBean(JdbcTemplate.class)
            .update(
                "UPDATE agent_execution_sessions SET lease_expires_at=CURRENT_TIMESTAMP - INTERVAL"
                    + " '1 minute' WHERE id=?",
                active.executionClaim().agentSessionClaim().sessionId());
      }
      if (stage.equals("UNROUTED")) {
        commands.summarize(
            f.runId, f.nodeId, UUID.randomUUID(), commands.get(f.runId, f.nodeId).revision());
        execute(original, f);
        var summary = commands.get(f.runId, f.nodeId);
        commands.complete(
            f.runId,
            f.nodeId,
            f.completionId,
            summary.revision(),
            summary.summaryRevisionId(),
            f.output);
      }
      before = commands.get(f.runId, f.nodeId);
    }
    try (var restarted = startApplication()) {
      var commands = restarted.getBean(DialogueCommands.class);
      assertThat(commands.get(f.runId, f.nodeId)).isEqualTo(before);
      assertThat(commands.messages(f.runId, f.nodeId, 0, 100).messages())
          .hasSize(
              stage.equals("UNROUTED") || stage.equals("WAITING")
                  ? (stage.equals("UNROUTED") ? 2 : 1)
                  : 2);
      if (stage.equals("ACTIVE")) {
        restarted.getBean(AgentExecutionRecoveryService.class).reconcileExpired();
        assertThat(commands.get(f.runId, f.nodeId).state()).isEqualTo(DialogueState.FAILED);
        assertThat(
                restarted
                    .getBean(DialogueTurnLifecycle.class)
                    .succeed(active, new AgentExecutionResult(new NodeRunOutput(REPLY), null)))
            .isFalse();
        assertThat(restarted.getBean(NodeRunRepository.class).findByWorkflowRunId(f.runId))
            .hasSize(1);
        return;
      }
      if (stage.equals("QUEUED_CHAT")) {
        execute(restarted, f);
      }
      if (!stage.equals("UNROUTED")) {
        assertThat(restarted.getBean(WorkflowRunUseCases.class).getWorkflowRun(f.runId).status())
            .isEqualTo(WorkflowRunStatus.RUNNING);
        commands.summarize(
            f.runId, f.nodeId, UUID.randomUUID(), commands.get(f.runId, f.nodeId).revision());
        execute(restarted, f);
        var summary = commands.get(f.runId, f.nodeId);
        commands.complete(
            f.runId,
            f.nodeId,
            f.completionId,
            summary.revision(),
            summary.summaryRevisionId(),
            f.output);
      }
      var completion = restarted.getBean(NodeRunCompletionWorker.class);
      completion.poll();
      completion.poll();
      var run = restarted.getBean(WorkflowRunUseCases.class).getWorkflowRun(f.runId);
      assertThat(run.nodeRuns()).hasSize(2);
      assertThat(
              run.nodeRuns().stream()
                  .filter(n -> n.id().equals(f.nodeId))
                  .findFirst()
                  .orElseThrow()
                  .routingCompletedAt())
          .isNotNull();
      var accepted = commands.get(f.runId, f.nodeId);
      commands.complete(
          f.runId,
          f.nodeId,
          f.completionId,
          accepted.revision() - 1,
          accepted.summaryRevisionId(),
          f.output);
      completion.poll();
      assertThat(restarted.getBean(NodeRunRepository.class).findByWorkflowRunId(f.runId))
          .hasSize(2);
    }
  }

  private static void execute(ServletWebServerApplicationContext context, Fixture f) {
    var state = context.getBean(DialogueCommands.class).get(f.runId, f.nodeId);
    var request =
        context.getBean(DialogueTurnLifecycle.class).claim(state.activeTurn().id()).orElseThrow();
    var claim = request.executionClaim().agentSessionClaim();
    var leases = context.getBean(AgentSessionLeaseService.class);
    leases.persistConversation(claim, "restart-conversation-" + claim.sessionId(), "fixture");
    leases.persistTurn(claim, "turn-" + claim.turnId());
    assertThat(
            context
                .getBean(DialogueTurnLifecycle.class)
                .succeed(request, new AgentExecutionResult(new NodeRunOutput(REPLY), null)))
        .isTrue();
  }

  private static Fixture fixture(ServletWebServerApplicationContext context) {
    Project project =
        context
            .getBean(ProjectUseCases.class)
            .createProject(new CreateProjectCommand("Dialogue restart " + UUID.randomUUID()));
    UUID agent = UUID.randomUUID();
    var now = Instant.now();
    context
        .getBean(AgentDefinitionRepository.class)
        .save(
            new AgentDefinition(
                agent,
                project.id(),
                "Groomer",
                "groomer",
                "Groom the task",
                new AgentOutputSchema("{\"type\":\"object\"}"),
                new AgentModelSelection("codex", "discovered-model", "medium"),
                now,
                now));
    var workflows = context.getBean(WorkflowUseCases.class);
    Workflow w = workflows.createWorkflow(project.id(), new CreateWorkflowCommand("Restart"));
    Node d =
        new Node(
            UUID.randomUUID(),
            agent,
            NodeInputMode.DEPENDENCIES_ONLY,
            List.of(new NodePort(UUID.randomUUID(), "Input", "Input", 0)),
            List.of(
                new NodePort(
                    UUID.randomUUID(), "Accept", "Accept", 0, DialogueOutputDisposition.ACCEPT)),
            new NodePosition(0, 0),
            NodeScopeMode.GLOBAL,
            null,
            null,
            NodeType.DIALOGUE);
    Node child =
        new Node(
            UUID.randomUUID(),
            null,
            NodeInputMode.DEPENDENCIES_ONLY,
            List.of(new NodePort(UUID.randomUUID(), "Input", "Input", 0)),
            List.of(new NodePort(UUID.randomUUID(), "Done", "Done", 0)),
            new NodePosition(0, 0),
            NodeScopeMode.GLOBAL,
            null,
            null,
            NodeType.MANUAL);
    workflows.updateWorkflow(
        w.id(),
        new SaveWorkflowCommand(
            w.name(),
            List.of(d, child),
            List.of(
                new WorkflowConnection(
                    UUID.randomUUID(),
                    d.outputs().getFirst().id(),
                    child.inputs().getFirst().id())),
            d.inputs().getFirst().id(),
            child.outputs().getFirst().id()));
    WorkflowRun run =
        context
            .getBean(WorkflowRunUseCases.class)
            .createWorkflowRun(w.id(), new CreateWorkflowRunCommand("Task"));
    return new Fixture(
        run.id(), run.nodeRuns().getFirst().id(), d.outputs().getFirst().id(), UUID.randomUUID());
  }

  private static ServletWebServerApplicationContext startApplication() {
    return (ServletWebServerApplicationContext)
        new SpringApplicationBuilder(ForgeAgentApplication.class)
            .initializers(
                context ->
                    context.addBeanFactoryPostProcessor(
                        factory ->
                            factory.registerSingleton(
                                "runtimeBoundaryVerifier",
                                org.mockito.Mockito.mock(
                                    com.sitionix.forgeagent.infrastructure.local.runtime
                                        .RuntimeBoundaryVerifier.class))))
            .properties(
                java.util.Map.of(
                    "forge.mcp.key-file",
                    ROOT.resolve("key").toString(),
                    "forge.mcp.database-credential-file",
                    ROOT.resolve("dialogue-restart-database").toString(),
                    "forge.agent.workspace-root",
                    MANAGED_WORKSPACE.toString()))
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + DATABASE.getJdbcUrl(),
                "--spring.datasource.username=" + DATABASE.getUsername(),
                "--spring.datasource.password=" + DATABASE.getPassword(),
                "--forge.agent.worker.scheduling-enabled=false",
                "--spring.main.banner-mode=off");
  }

  private record Fixture(UUID runId, UUID nodeId, UUID output, UUID completionId) {}
}
