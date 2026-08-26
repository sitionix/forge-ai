package com.sitionix.forgeagent.it.tests;

import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_A_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_B_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_C_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_ALPHA_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.WORKFLOW_ID;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEFINITION;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT_REPOSITORY;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.WORKFLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.application.runtime.AgentExecutionResult;
import com.sitionix.forgeagent.application.runtime.NodeExecutionClaim;
import com.sitionix.forgeagent.application.runtime.NodeRunCompletionPersistence;
import com.sitionix.forgeagent.application.runtime.NodeRunCompletionProcessor;
import com.sitionix.forgeagent.application.runtime.NodeRunCompletionWorker;
import com.sitionix.forgeagent.application.runtime.NodeRunLifecycle;
import com.sitionix.forgeagent.application.runtime.SelectedOutputRoutingPolicy;
import com.sitionix.forgeagent.application.runtime.WorkflowExecutionCoordinator;
import com.sitionix.forgeagent.application.usecase.AgentUseCases;
import com.sitionix.forgeagent.application.usecase.CreateProjectTaskCommand;
import com.sitionix.forgeagent.application.usecase.CreateWorkflowRunCommand;
import com.sitionix.forgeagent.application.usecase.SaveAgentCommand;
import com.sitionix.forgeagent.application.usecase.SaveWorkflowCommand;
import com.sitionix.forgeagent.application.usecase.ProjectTaskUseCases;
import com.sitionix.forgeagent.application.usecase.WorkflowRunUseCases;
import com.sitionix.forgeagent.application.usecase.WorkflowUseCases;
import com.sitionix.forgeagent.domain.model.AgentModelSelection;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.model.ConnectionResolutionType;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodePort;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.NodeScopeMode;
import com.sitionix.forgeagent.domain.model.ProjectTaskDetails;
import com.sitionix.forgeagent.domain.model.WorkflowConnection;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.ConnectionResolutionRepository;
import com.sitionix.forgeagent.domain.port.ExecutionFrameRepository;
import com.sitionix.forgeagent.domain.port.InputActivationResolutionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectRepositoryEntity;
import com.sitionix.forgeit.core.test.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@IntegrationTest
class ForgeAgentPortAwareExecutionIT {

    private static final UUID A = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID B = UUID.fromString("90000000-0000-4000-8000-000000000002");
    private static final UUID C = UUID.fromString("90000000-0000-4000-8000-000000000003");
    private static final UUID D = UUID.fromString("90000000-0000-4000-8000-000000000004");
    private static final UUID X = UUID.fromString("90000000-0000-4000-8000-000000000005");
    private static final UUID IMPLEMENTER = UUID.fromString("90000000-0000-4000-8000-000000000006");
    private static final UUID STRATEGY = UUID.fromString("90000000-0000-4000-8000-000000000007");
    private static final UUID CODE = UUID.fromString("90000000-0000-4000-8000-000000000008");
    private static final UUID REPOSITORY_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");

    private static final UUID A_OUT = UUID.fromString("91000000-0000-4000-8000-000000000001");
    private static final UUID A_IN = UUID.fromString("92000000-0000-4000-8000-000000000001");
    private static final UUID A_PASS = UUID.fromString("91000000-0000-4000-8000-000000000021");
    private static final UUID A_RETURN = UUID.fromString("91000000-0000-4000-8000-000000000022");
    private static final UUID B_IN = UUID.fromString("92000000-0000-4000-8000-000000000002");
    private static final UUID B_IN_UPDATED = UUID.fromString("92000000-0000-4000-8000-000000000022");
    private static final UUID B_OUT = UUID.fromString("91000000-0000-4000-8000-000000000002");
    private static final UUID C_IN = UUID.fromString("92000000-0000-4000-8000-000000000003");
    private static final UUID C_OUT = UUID.fromString("91000000-0000-4000-8000-000000000003");
    private static final UUID C_OTHER = UUID.fromString("91000000-0000-4000-8000-000000000013");
    private static final UUID D_IN = UUID.fromString("92000000-0000-4000-8000-000000000004");
    private static final UUID D_OUT = UUID.fromString("91000000-0000-4000-8000-000000000004");
    private static final UUID X_IN = UUID.fromString("92000000-0000-4000-8000-000000000005");
    private static final UUID X_OUT = UUID.fromString("91000000-0000-4000-8000-000000000005");

    private static final UUID IMPLEMENTER_OUT = UUID.fromString("91000000-0000-4000-8000-000000000006");
    private static final UUID IMPLEMENTER_INITIAL_IN = UUID.fromString("92000000-0000-4000-8000-000000000016");
    private static final UUID IMPLEMENTER_REVIEW_IN = UUID.fromString("92000000-0000-4000-8000-000000000006");
    private static final UUID STRATEGY_IN = UUID.fromString("92000000-0000-4000-8000-000000000007");
    private static final UUID STRATEGY_PASS = UUID.fromString("91000000-0000-4000-8000-000000000007");
    private static final UUID STRATEGY_RETURN = UUID.fromString("91000000-0000-4000-8000-000000000017");
    private static final UUID CODE_IN = UUID.fromString("92000000-0000-4000-8000-000000000008");
    private static final UUID CODE_PASS = UUID.fromString("91000000-0000-4000-8000-000000000008");
    private static final UUID CODE_RETURN = UUID.fromString("91000000-0000-4000-8000-000000000018");

    @Autowired
    private ForgeAgentTestManager forgeIt;
    @Autowired
    private WorkflowUseCases workflowUseCases;
    @Autowired
    private WorkflowRunUseCases workflowRunUseCases;
    @Autowired
    private ProjectTaskUseCases projectTaskUseCases;
    @Autowired
    private AgentUseCases agentUseCases;
    @Autowired
    private NodeRunLifecycle lifecycle;
    @Autowired
    private NodeRunCompletionPersistence completionPersistence;
    @Autowired
    private NodeRunCompletionProcessor completionProcessor;
    @Autowired
    private NodeRunCompletionWorker completionWorker;
    @Autowired
    private WorkflowExecutionCoordinator coordinator;
    @Autowired
    private NodeRunRepository nodeRunRepository;
    @Autowired
    private WorkflowRunRepository workflowRunRepository;
    @Autowired
    private ConnectionResolutionRepository resolutionRepository;
    @Autowired
    private ExecutionFrameRepository frameRepository;
    @Autowired
    private InputActivationResolutionRepository activationResolutionRepository;
    @Autowired
    private EntityManager entityManager;

    @MockBean
    private OutputSelector outputSelector;

    @AfterEach
    void removeRepositoryWorkspaceFixture() throws IOException {
        final Path projectWorkspace = this.projectWorkspace();
        if (!Files.exists(projectWorkspace)) {
            return;
        }
        try (var paths = Files.walk(projectWorkspace)) {
            for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void executesLinearWorkflowEndToEnd() {
        this.seed();
        this.saveLinearWorkflow();

        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Build feature."));
        this.complete(this.onlyPending(run.id(), A), "{\"step\":\"A\"}");
        this.complete(this.onlyPending(run.id(), B), "{\"step\":\"B\"}");
        final NodeRun c = this.onlyPending(run.id(), C);
        this.complete(c, "{\"step\":\"C\"}");

        final WorkflowRun finished = this.workflowRunRepository.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
        assertThat(finished.result()).isEqualTo(new NodeRunOutput("{\"step\": \"C\"}"));
        assertThat(finished.resultSourceNodeRunId()).isEqualTo(c.id());
        assertThat(finished.nodeRuns()).extracting(NodeRun::sourceNodeId).containsExactly(A, B, C);
        assertThat(finished.connectionResolutions()).hasSize(2)
                .allSatisfy(resolution -> assertThat(resolution.type()).isEqualTo(ConnectionResolutionType.DELIVERED));
        assertThat(this.nodeRunRepository.findById(c.id()).orElseThrow().selectedOutputPortId()).isEqualTo(C_OUT);
    }

    @Test
    void projectTaskExecutionPersistsWorkflowRunResultAndReturnsTaskResult() {
        this.seed();
        this.saveLinearWorkflow();

        final ProjectTaskDetails created = this.projectTaskUseCases.createProjectTask(PROJECT_ALPHA_ID, new CreateProjectTaskCommand(
                "Build feature",
                "Build feature.",
                WORKFLOW_ID,
                List.of(REPOSITORY_ID)
        ));
        final UUID runId = created.runs().getFirst().id();
        this.complete(this.onlyPending(runId, A), "{\"step\":\"A\"}");
        this.complete(this.onlyPending(runId, B), "{\"step\":\"B\"}");
        final NodeRun c = this.onlyPending(runId, C);
        this.complete(c, "{\"business\":\"result\"}");
        this.entityManager.clear();

        final WorkflowRun finished = this.workflowRunRepository.findById(runId).orElseThrow();
        assertThat(finished.status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
        assertThat(finished.result()).isEqualTo(new NodeRunOutput("{\"business\": \"result\"}"));
        assertThat(finished.resultSourceNodeRunId()).isEqualTo(c.id());
        assertThat(this.projectTaskUseCases.getProjectTask(created.id()).result())
                .isEqualTo(new NodeRunOutput("{\"business\": \"result\"}"));
    }

    @Test
    void passAndReturnCreatesOneReentryWithOnlyReturnedFeedback() {
        this.seed();
        this.saveReviewerWorkflow();
        when(this.outputSelector.selectOutput(any(), any(), any())).thenReturn(STRATEGY_PASS, CODE_RETURN);

        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Implement feature."));
        final NodeRun implementerOne = this.onlyPending(run.id(), IMPLEMENTER);
        this.complete(implementerOne, "{\"patch\":\"v1\"}");
        this.complete(this.onlyPending(run.id(), STRATEGY), "{\"strategy\":\"ok\"}");
        assertThat(this.pendingForSource(run.id(), IMPLEMENTER)).isEmpty();
        this.complete(this.onlyPending(run.id(), CODE), "{\"code\":\"fix retry\"}");

        final List<NodeRun> implementerRuns = this.nodeRuns(run.id(), IMPLEMENTER);
        assertThat(implementerRuns).hasSize(2);
        final NodeRun implementerTwo = implementerRuns.get(1);
        assertThat(implementerTwo.activationFrameId()).isEqualTo(implementerOne.executionFrameId());
        assertThat(implementerTwo.executionFrameId()).isNotEqualTo(implementerOne.executionFrameId());
        assertThat(this.resolutionRepository.findConsumedByNodeRunId(implementerTwo.id())).singleElement()
                .satisfies(resolution -> assertThat(resolution.sourceConnectionId()).isEqualTo(this.connectionId(4)));
        assertThat(this.lifecycle.tryStart(implementerTwo.id()).orElseThrow().inputEnvelope().contributions()).hasSize(1);
    }

    @Test
    void concurrentReturnAndReturnCreatesOneReentryAndOneChildFrame() throws Exception {
        this.seed();
        this.saveReviewerWorkflow();
        when(this.outputSelector.selectOutput(any(), any(), any())).thenAnswer(invocation -> invocation.<List<com.sitionix.forgeagent.domain.model.RunPort>>getArgument(1).stream()
                .filter(port -> port.name().equals("Return"))
                .findFirst()
                .orElseThrow()
                .sourcePortId());

        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Implement feature."));
        this.complete(this.onlyPending(run.id(), IMPLEMENTER), "{\"patch\":\"v1\"}");
        final NodeRun strategy = this.start(this.onlyPending(run.id(), STRATEGY));
        final NodeRun code = this.start(this.onlyPending(run.id(), CODE));

        try (var executor = Executors.newFixedThreadPool(2)) {
            final var first = executor.submit(() -> this.lifecycle.succeed(strategy.id(), new NodeRunOutput("{\"strategy\":\"return\"}")));
            final var second = executor.submit(() -> this.lifecycle.succeed(code.id(), new NodeRunOutput("{\"code\":\"return\"}")));
            first.get();
            second.get();
        }

        final List<NodeRun> implementerRuns = this.nodeRuns(run.id(), IMPLEMENTER);
        assertThat(implementerRuns).hasSize(2);
        assertThat(this.resolutionRepository.findConsumedByNodeRunId(implementerRuns.get(1).id())).hasSize(2);
        assertThat(this.frameRepository.findByWorkflowRunId(run.id())).hasSize(2);
    }

    @Test
    void passAndPassClosesReviewWithoutReentry() {
        this.seed();
        this.saveReviewerWorkflow();
        when(this.outputSelector.selectOutput(any(), any(), any())).thenReturn(STRATEGY_PASS, CODE_PASS);

        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Implement feature."));
        this.complete(this.onlyPending(run.id(), IMPLEMENTER), "{\"patch\":\"v1\"}");
        this.complete(this.onlyPending(run.id(), STRATEGY), "{\"strategy\":\"pass\"}");
        this.complete(this.onlyPending(run.id(), CODE), "{\"code\":\"pass\"}");

        assertThat(this.nodeRuns(run.id(), IMPLEMENTER)).hasSize(1);
        assertThat(this.activationResolutionRepository.find(run.id(), this.rootFrame(run.id()), IMPLEMENTER_REVIEW_IN, null)).isPresent()
                .get()
                .satisfies(resolution -> assertThat(resolution.activatedNodeRunId()).isNull());
        assertThat(this.workflowRunRepository.findById(run.id()).orElseThrow().status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
    }

    @Test
    void delayedDeepBranchWaitsThenConsumesBothContributions() {
        this.seed();
        this.saveDeepWorkflow(false);

        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Fan in."));
        this.complete(this.onlyPending(run.id(), A), "{\"step\":\"A\"}");
        this.complete(this.onlyPending(run.id(), B), "{\"step\":\"B\"}");
        assertThat(this.pendingForSource(run.id(), X)).isEmpty();
        this.complete(this.onlyPending(run.id(), C), "{\"step\":\"C\"}");
        this.complete(this.onlyPending(run.id(), D), "{\"step\":\"D\"}");

        final NodeRun x = this.onlyPending(run.id(), X);
        assertThat(this.resolutionRepository.findConsumedByNodeRunId(x.id())).hasSize(2);
    }

    @Test
    void closingDeepBranchReevaluatesFanInAndConsumesDeliveredBranchOnly() {
        this.seed();
        this.saveDeepWorkflow(true);
        when(this.outputSelector.selectOutput(any(), any(), any())).thenReturn(C_OTHER);

        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Fan in."));
        this.complete(this.onlyPending(run.id(), A), "{\"step\":\"A\"}");
        this.complete(this.onlyPending(run.id(), B), "{\"step\":\"B\"}");
        assertThat(this.pendingForSource(run.id(), X)).isEmpty();
        this.complete(this.onlyPending(run.id(), C), "{\"step\":\"C\"}");

        final NodeRun x = this.onlyPending(run.id(), X);
        assertThat(this.pendingForSource(run.id(), D)).isEmpty();
        assertThat(this.resolutionRepository.findConsumedByNodeRunId(x.id())).singleElement()
                .satisfies(resolution -> assertThat(resolution.sourceNodeRunId()).isEqualTo(this.nodeRuns(run.id(), B).get(0).id()));
    }

    @Test
    void secondRoundReentryUsesNewFrameWithoutCrossFrameContributionMixing() {
        this.seed();
        this.saveReviewerWorkflow();
        when(this.outputSelector.selectOutput(any(), any(), any())).thenAnswer(invocation -> {
            final NodeRunOutput output = invocation.getArgument(0);
            final String targetName = output.jsonValue().contains("pass") ? "Pass" : "Return";
            return this.outputNamed(invocation.getArgument(1), targetName);
        });

        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Implement feature."));
        final NodeRun implementerOne = this.onlyPending(run.id(), IMPLEMENTER);
        this.complete(implementerOne, "{\"patch\":\"v1\"}");
        this.complete(this.onlyPending(run.id(), STRATEGY), "{\"strategy\":\"pass\"}");
        this.complete(this.onlyPending(run.id(), CODE), "{\"code\":\"return\"}");

        final NodeRun implementerTwo = this.nodeRuns(run.id(), IMPLEMENTER).get(1);
        final UUID frameTwo = implementerTwo.executionFrameId();
        final List<ConnectionResolution> roundOneConsumed = this.resolutionRepository.findConsumedByNodeRunId(implementerTwo.id());
        assertThat(roundOneConsumed).singleElement()
                .satisfies(resolution -> assertThat(resolution.executionFrameId()).isEqualTo(implementerOne.executionFrameId()));

        this.complete(implementerTwo, "{\"patch\":\"v2\"}");
        this.complete(this.onlyPending(run.id(), STRATEGY), "{\"strategy\":\"return\"}");
        this.complete(this.onlyPending(run.id(), CODE), "{\"code\":\"return\"}");

        final List<NodeRun> implementerRuns = this.nodeRuns(run.id(), IMPLEMENTER);
        assertThat(implementerRuns).hasSize(3);
        final NodeRun implementerThree = implementerRuns.get(2);
        assertThat(implementerThree.activationFrameId()).isEqualTo(frameTwo);
        assertThat(implementerThree.executionFrameId()).isNotEqualTo(frameTwo);
        assertThat(this.resolutionRepository.findConsumedByNodeRunId(implementerThree.id()))
                .hasSize(2)
                .allSatisfy(resolution -> assertThat(resolution.executionFrameId()).isEqualTo(frameTwo));
        assertThat(roundOneConsumed).noneMatch(resolution -> resolution.consumedByNodeRunId().equals(implementerThree.id()));
    }

    @Test
    void invalidAiRoutingResultFailsNodeRunAndWorkflowWithoutLeavingRunningRows() {
        this.seed();
        this.saveReviewerWorkflow();
        when(this.outputSelector.selectOutput(any(), any(), any())).thenReturn(UUID.fromString("aaaaaaaa-0000-4000-8000-000000000000"));

        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Implement feature."));
        this.complete(this.onlyPending(run.id(), IMPLEMENTER), "{\"patch\":\"v1\"}");
        final NodeRun strategy = this.onlyPending(run.id(), STRATEGY);

        this.complete(strategy, "{\"strategy\":\"unknown route\"}");
        this.entityManager.clear();

        assertThat(this.nodeRunRepository.findById(strategy.id()).orElseThrow()).satisfies(nodeRun -> {
            assertThat(nodeRun.status()).isEqualTo(NodeRunStatus.FAILED);
            assertThat(nodeRun.failure()).isNotNull();
            assertThat(nodeRun.failure().code()).isEqualTo(SelectedOutputRoutingPolicy.INVALID_SELECTED_OUTPUT_PORT);
        });
        assertThat(this.nodeRunRepository.findByWorkflowRunId(run.id())).noneMatch(nodeRun -> nodeRun.status() == NodeRunStatus.RUNNING);
        assertThat(this.workflowRunRepository.findById(run.id()).orElseThrow().status()).isEqualTo(WorkflowRunStatus.FAILED);
    }

    @Test
    void crashBetweenBusinessSuccessAndRoutingIsRecoveredByCompletionWorker() {
        this.seed();
        this.saveLinearWorkflow();

        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Build feature."));
        final NodeRun runningA = this.start(this.onlyPending(run.id(), A));
        this.completionPersistence.markBusinessSucceeded(runningA.id(), new NodeRunOutput("{\"step\":\"A\"}"));
        this.entityManager.clear();

        assertThat(this.nodeRunRepository.findById(runningA.id()).orElseThrow()).satisfies(nodeRun -> {
            assertThat(nodeRun.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
            assertThat(nodeRun.routingCompletedAt()).isNull();
        });
        assertThat(this.pendingForSource(run.id(), B)).isEmpty();

        this.completionWorker.poll();
        this.entityManager.clear();

        assertThat(this.nodeRunRepository.findById(runningA.id()).orElseThrow()).satisfies(nodeRun -> {
            assertThat(nodeRun.selectedOutputPortId()).isEqualTo(A_OUT);
            assertThat(nodeRun.routingCompletedAt()).isNotNull();
        });
        assertThat(this.onlyPending(run.id(), B)).isNotNull();
    }

    @Test
    void deterministicRoutingRunsOutsideDatabaseTransactionThroughCompletionProcessor() {
        this.seed();
        this.saveReviewerWorkflow();
        final AtomicBoolean observedNoTransaction = new AtomicBoolean(false);
        when(this.outputSelector.selectOutput(any(), any(), any())).thenAnswer(invocation -> {
            observedNoTransaction.set(!TransactionSynchronizationManager.isActualTransactionActive());
            return STRATEGY_PASS;
        });

        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Implement feature."));
        this.complete(this.onlyPending(run.id(), IMPLEMENTER), "{\"patch\":\"v1\"}");
        final NodeRun strategy = this.start(this.onlyPending(run.id(), STRATEGY));
        this.completionPersistence.markBusinessSucceeded(strategy.id(), new NodeRunOutput("{\"strategy\":\"pass\"}"));

        this.completionProcessor.process(strategy.id());

        assertThat(observedNoTransaction).isTrue();
        assertThat(this.nodeRunRepository.findById(strategy.id()).orElseThrow().routingCompletedAt()).isNotNull();
    }

    @Test
    void concurrentCompletionProcessingAppliesRoutingExactlyOnce() throws Exception {
        this.seed();
        this.saveSingleAiReturnWorkflow();
        when(this.outputSelector.selectOutput(any(), any(), any())).thenReturn(A_RETURN);

        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Route once."));
        final NodeRun runningA = this.start(this.onlyPending(run.id(), A));
        this.completionPersistence.markBusinessSucceeded(runningA.id(),
                new AgentExecutionResult(new NodeRunOutput("{\"route\":\"return\"}"), A_RETURN));

        try (var executor = Executors.newFixedThreadPool(2)) {
            final var first = executor.submit(() -> this.completionProcessor.process(runningA.id()));
            final var second = executor.submit(() -> this.completionProcessor.process(runningA.id()));
            first.get();
            second.get();
        }
        this.entityManager.clear();

        final NodeRun routedA = this.nodeRunRepository.findById(runningA.id()).orElseThrow();
        assertThat(routedA.selectedOutputPortId()).isEqualTo(A_RETURN);
        assertThat(routedA.routingCompletedAt()).isNotNull();
        assertThat(this.workflowRunRepository.findById(run.id()).orElseThrow().connectionResolutions()).singleElement()
                .satisfies(resolution -> assertThat(resolution.sourceNodeRunId()).isEqualTo(runningA.id()));
        assertThat(this.pendingForSource(run.id(), B)).hasSize(1);
        final NodeRun b = this.onlyPending(run.id(), B);
        assertThat(this.activationResolutionRepository.find(run.id(), runningA.executionFrameId(), B_IN, null)).isPresent()
                .get()
                .satisfies(resolution -> assertThat(resolution.activatedNodeRunId()).isEqualTo(b.id()));
        assertThat(this.frameRepository.findByWorkflowRunId(run.id())).hasSize(1);
    }

    @Test
    void workflowCannotCompleteWhileSuccessfulNodeRunStillNeedsRouting() {
        this.seed();
        this.saveTerminalWorkflow();

        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Terminal."));
        final NodeRun runningA = this.start(this.onlyPending(run.id(), A));
        this.completionPersistence.markBusinessSucceeded(runningA.id(), new NodeRunOutput("{\"done\":true}"));
        this.coordinator.reconcile(this.workflowRunRepository.findById(run.id()).orElseThrow());

        assertThat(this.nodeRunRepository.findById(runningA.id()).orElseThrow().routingCompletedAt()).isNull();
        assertThat(this.workflowRunRepository.findById(run.id()).orElseThrow().status()).isEqualTo(WorkflowRunStatus.RUNNING);
    }

    @Test
    @Transactional
    void legacyPendingNodeRunWithoutExecutionFrameCannotBeClaimedByNewWorkerQuery() {
        this.seed();
        final UUID legacyRunId = UUID.fromString("94000000-0000-4000-8000-000000000001");
        final UUID legacyNodeRunId = UUID.fromString("94000000-0000-4000-8000-000000000002");
        this.entityManager.createNativeQuery("""
                INSERT INTO workflow_runs (id, project_id, source_workflow_id, workflow_name, input, status, created_at, started_at)
                VALUES (:runId, :projectId, :workflowId, 'Legacy active', 'Legacy input', 'RUNNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)
                .setParameter("runId", legacyRunId)
                .setParameter("projectId", PROJECT_ALPHA_ID)
                .setParameter("workflowId", WORKFLOW_ID)
                .executeUpdate();
        this.entityManager.createNativeQuery("""
                INSERT INTO node_runs (
                    id, workflow_run_id, source_node_id, source_agent_id, agent_name, agent_instructions,
                    agent_output_schema, input_mode, position_x, position_y, status,
                    execution_model_provider_id, execution_model_id, execution_model_effort_id, created_at
                )
                VALUES (
                    :nodeRunId, :runId, :sourceNodeId, :agentId, 'Legacy Agent', 'Legacy instructions.',
                    CAST(:schema AS jsonb), 'DEPENDENCIES_ONLY', 0, 0, 'PENDING',
                    'codex', 'discovered-model', 'medium', CURRENT_TIMESTAMP
                )
                """)
                .setParameter("nodeRunId", legacyNodeRunId)
                .setParameter("runId", legacyRunId)
                .setParameter("sourceNodeId", A)
                .setParameter("agentId", AGENT_A_ID)
                .setParameter("schema", "{\"type\":\"object\"}")
                .executeUpdate();
        this.entityManager.flush();

        assertThat(this.nodeRunRepository.findPendingIds()).doesNotContain(legacyNodeRunId);
        assertThat(this.lifecycle.tryStart(legacyNodeRunId)).isEmpty();
    }

    @Test
    void activeRunUsesSnapshottedTopologyAndAgentAfterLiveWorkflowMutation() {
        this.seed();
        this.saveLinearWorkflow();

        final WorkflowRun runOne = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("First run."));
        this.saveLinearWorkflowWithUpdatedBPort();
        this.agentUseCases.updateAgent(AGENT_B_ID, new SaveAgentCommand(
                "Agent B",
                "Updated live Agent B instructions.",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                new AgentModelSelection("codex", "model-b", "xhigh")
        ));

        this.complete(this.onlyPending(runOne.id(), A), "{\"step\":\"A1\"}");
        final NodeExecutionClaim oldBClaim = this.lifecycle.tryStart(this.onlyPending(runOne.id(), B).id()).orElseThrow();
        assertThat(oldBClaim.agentInstructions()).isEqualTo("Do work for Agent B.");
        assertThat(oldBClaim.executionModel().modelId()).isEqualTo("discovered-model");
        assertThat(oldBClaim.inputEnvelope().entryInputPort().sourcePortId()).isEqualTo(B_IN);
        assertThat(oldBClaim.inputEnvelope().entryInputPort().name()).isEqualTo("Input");

        final WorkflowRun runTwo = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Second run."));
        this.complete(this.onlyPending(runTwo.id(), A), "{\"step\":\"A2\"}");
        final NodeExecutionClaim newBClaim = this.lifecycle.tryStart(this.onlyPending(runTwo.id(), B).id()).orElseThrow();
        assertThat(newBClaim.agentInstructions()).isEqualTo("Updated live Agent B instructions.");
        assertThat(newBClaim.executionModel().modelId()).isEqualTo("model-b");
        assertThat(newBClaim.executionModel().effortId()).isEqualTo("xhigh");
        assertThat(newBClaim.inputEnvelope().entryInputPort().sourcePortId()).isEqualTo(B_IN_UPDATED);
        assertThat(newBClaim.inputEnvelope().entryInputPort().name()).isEqualTo("Updated Input");
        assertThat(newBClaim.inputEnvelope().entryInputPort().description()).isEqualTo("Updated B input description.");
    }

    private void seed() {
        try {
            Files.createDirectories(this.projectWorkspace().resolve("repository").resolve(".git"));
        } catch (final IOException exception) {
            throw new IllegalStateException("Failed to create Forge repository workspace fixture.", exception);
        }
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT.withJson("project_alpha.json"))
                .to(PROJECT_REPOSITORY.withEntity(this.projectRepositoryEntity()))
                .to(AGENT_DEFINITION.withJson("agent_a.json"))
                .to(AGENT_DEFINITION.withJson("agent_b.json"))
                .to(AGENT_DEFINITION.withJson("agent_c.json"))
                .to(WORKFLOW.withJson("workflow_alpha.json"))
                .build();
    }

    private Path projectWorkspace() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve(".git"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Forge root could not be resolved for integration test.");
        }
        return current.resolve("forge-projects").resolve(PROJECT_ALPHA_ID.toString());
    }

    private ProjectRepositoryEntity projectRepositoryEntity() {
        final ProjectRepositoryEntity entity = new ProjectRepositoryEntity();
        entity.setId(REPOSITORY_ID);
        entity.setProjectId(PROJECT_ALPHA_ID);
        entity.setRemoteUrl("https://example.com/forge/repository.git");
        entity.setCreatedAt(java.time.Instant.parse("2026-08-10T10:00:00Z"));
        return entity;
    }

    private void saveLinearWorkflow() {
        this.workflowUseCases.updateWorkflow(WORKFLOW_ID, new SaveWorkflowCommand(
                "Full Testing",
                List.of(
                        this.node(A, AGENT_A_ID, List.of(this.port(A_IN, "Input")), List.of(this.port(A_OUT, "Done")), 0),
                        this.node(B, AGENT_B_ID, List.of(this.port(B_IN, "Input")), List.of(this.port(B_OUT, "Done")), 1),
                        this.node(C, AGENT_C_ID, List.of(this.port(C_IN, "Input")), List.of(this.port(C_OUT, "Done")), 2)
                ),
                List.of(
                        this.connection(1, A_OUT, B_IN),
                        this.connection(2, B_OUT, C_IN)
                ),
                A_IN,
                C_OUT
        ));
    }

    private void saveLinearWorkflowWithUpdatedBPort() {
        this.workflowUseCases.updateWorkflow(WORKFLOW_ID, new SaveWorkflowCommand(
                "Full Testing",
                List.of(
                        this.node(A, AGENT_A_ID, List.of(this.port(A_IN, "Input")), List.of(this.port(A_OUT, "Done")), 0),
                        this.node(B, AGENT_B_ID, List.of(this.port(B_IN_UPDATED, "Updated Input", "Updated B input description.", 0)), List.of(this.port(B_OUT, "Done")), 1),
                        this.node(C, AGENT_C_ID, List.of(this.port(C_IN, "Input")), List.of(this.port(C_OUT, "Done")), 2)
                ),
                List.of(
                        this.connection(20, A_OUT, B_IN_UPDATED),
                        this.connection(2, B_OUT, C_IN)
                ),
                A_IN,
                C_OUT
        ));
    }

    private void saveTerminalWorkflow() {
        this.workflowUseCases.updateWorkflow(WORKFLOW_ID, new SaveWorkflowCommand(
                "Full Testing",
                List.of(this.node(A, AGENT_A_ID, List.of(this.port(A_IN, "Input")), List.of(this.port(A_OUT, "Done")), 0)),
                List.of(),
                A_IN,
                A_OUT
        ));
    }

    private void saveSingleAiReturnWorkflow() {
        this.workflowUseCases.updateWorkflow(WORKFLOW_ID, new SaveWorkflowCommand(
                "Full Testing",
                List.of(
                        this.node(A, AGENT_A_ID, List.of(this.port(A_IN, "Input")), List.of(this.port(A_PASS, "Pass", 0), this.port(A_RETURN, "Return", 1)), 0),
                        this.node(B, AGENT_B_ID, List.of(this.port(B_IN, "Input")), List.of(this.port(B_OUT, "Done")), 1)
                ),
                List.of(this.connection(30, A_RETURN, B_IN)),
                A_IN,
                B_OUT
        ));
    }

    private void saveReviewerWorkflow() {
        this.workflowUseCases.updateWorkflow(WORKFLOW_ID, new SaveWorkflowCommand(
                "Full Testing",
                List.of(
                        this.node(IMPLEMENTER, AGENT_A_ID,
                                List.of(this.port(IMPLEMENTER_INITIAL_IN, "Initial", 0), this.port(IMPLEMENTER_REVIEW_IN, "Review", 1)),
                                List.of(this.port(IMPLEMENTER_OUT, "Done")), 0),
                        this.node(STRATEGY, AGENT_B_ID, List.of(this.port(STRATEGY_IN, "Input")),
                                List.of(this.port(STRATEGY_PASS, "Pass", 0), this.port(STRATEGY_RETURN, "Return", 1)), 1),
                        this.node(CODE, AGENT_C_ID, List.of(this.port(CODE_IN, "Input")),
                                List.of(this.port(CODE_PASS, "Pass", 0), this.port(CODE_RETURN, "Return", 1)), 2)
                ),
                List.of(
                        this.connection(1, IMPLEMENTER_OUT, STRATEGY_IN),
                        this.connection(2, IMPLEMENTER_OUT, CODE_IN),
                        this.connection(3, STRATEGY_RETURN, IMPLEMENTER_REVIEW_IN),
                        this.connection(4, CODE_RETURN, IMPLEMENTER_REVIEW_IN)
                ),
                IMPLEMENTER_INITIAL_IN,
                CODE_PASS
        ));
    }

    private void saveDeepWorkflow(final boolean closeCPath) {
        this.workflowUseCases.updateWorkflow(WORKFLOW_ID, new SaveWorkflowCommand(
                "Full Testing",
                List.of(
                        this.node(A, AGENT_A_ID, List.of(this.port(A_IN, "Input")), List.of(this.port(A_OUT, "Done")), 0),
                        this.node(B, AGENT_B_ID, List.of(this.port(B_IN, "Input")), List.of(this.port(B_OUT, "Done")), 1),
                        this.node(C, AGENT_C_ID, List.of(this.port(C_IN, "Input")),
                                closeCPath ? List.of(this.port(C_OUT, "To D", 0), this.port(C_OTHER, "Other", 1)) : List.of(this.port(C_OUT, "To D")), 2),
                        this.node(D, AGENT_A_ID, List.of(this.port(D_IN, "Input")), List.of(this.port(D_OUT, "Done")), 3),
                        this.node(X, AGENT_B_ID, List.of(this.port(X_IN, "Input")), List.of(this.port(X_OUT, "Done")), 4)
                ),
                List.of(
                        this.connection(1, A_OUT, B_IN),
                        this.connection(2, A_OUT, C_IN),
                        this.connection(3, B_OUT, X_IN),
                        this.connection(4, C_OUT, D_IN),
                        this.connection(5, D_OUT, X_IN)
                ),
                A_IN,
                X_OUT
        ));
    }

    private Node node(final UUID id, final UUID agentId, final List<NodePort> inputs, final List<NodePort> outputs, final int x) {
        return new Node(id, agentId, NodeInputMode.DEPENDENCIES_ONLY, inputs, outputs,
                new NodePosition(x * 100.0, 0.0), NodeScopeMode.GLOBAL);
    }

    private NodePort port(final UUID id, final String name) {
        return this.port(id, name, 0);
    }

    private NodePort port(final UUID id, final String name, final int order) {
        return this.port(id, name, name + " description.", order);
    }

    private NodePort port(final UUID id, final String name, final String description, final int order) {
        return new NodePort(id, name, description, order);
    }

    private UUID outputNamed(final List<com.sitionix.forgeagent.domain.model.RunPort> outputs, final String name) {
        return outputs.stream()
                .filter(port -> port.name().equals(name))
                .findFirst()
                .orElseThrow()
                .sourcePortId();
    }

    private WorkflowConnection connection(final int index, final UUID sourceOutputPortId, final UUID targetInputPortId) {
        return new WorkflowConnection(this.connectionId(index), sourceOutputPortId, targetInputPortId);
    }

    private UUID connectionId(final int index) {
        return UUID.fromString("93000000-0000-4000-8000-" + String.format("%012d", index));
    }

    private NodeRun start(final NodeRun nodeRun) {
        final NodeExecutionClaim claim = this.lifecycle.tryStart(nodeRun.id()).orElseThrow();
        return this.nodeRunRepository.findById(claim.nodeRunId()).orElseThrow();
    }

    private void complete(final NodeRun nodeRun, final String output) {
        final NodeExecutionClaim claim = this.lifecycle.tryStart(nodeRun.id()).orElseThrow();
        final NodeRunOutput businessOutput = new NodeRunOutput(output);
        final UUID selected = claim.availableOutputs().size() > 1
                ? this.outputSelector.selectOutput(businessOutput, claim.availableOutputs(), claim.executionModel())
                : null;
        this.lifecycle.succeed(claim.nodeRunId(), new AgentExecutionResult(businessOutput, selected));
    }

    private interface OutputSelector {
        UUID selectOutput(NodeRunOutput output, List<com.sitionix.forgeagent.domain.model.RunPort> outputs,
                          com.sitionix.forgeagent.domain.model.NodeRunExecutionModel executionModel);
    }

    private NodeRun onlyPending(final UUID workflowRunId, final UUID sourceNodeId) {
        return this.pendingForSource(workflowRunId, sourceNodeId).stream().findFirst().orElseThrow();
    }

    private List<NodeRun> pendingForSource(final UUID workflowRunId, final UUID sourceNodeId) {
        return this.nodeRuns(workflowRunId, sourceNodeId).stream()
                .filter(nodeRun -> nodeRun.status() == NodeRunStatus.PENDING)
                .toList();
    }

    private List<NodeRun> nodeRuns(final UUID workflowRunId, final UUID sourceNodeId) {
        return this.nodeRunRepository.findByWorkflowRunId(workflowRunId).stream()
                .filter(nodeRun -> nodeRun.sourceNodeId().equals(sourceNodeId))
                .sorted(Comparator.comparing(NodeRun::createdAt).thenComparing(NodeRun::id))
                .toList();
    }

    private UUID rootFrame(final UUID workflowRunId) {
        return this.frameRepository.findByWorkflowRunId(workflowRunId).stream()
                .filter(frame -> frame.parentFrameId() == null)
                .findFirst()
                .orElseThrow()
                .id();
    }
}
