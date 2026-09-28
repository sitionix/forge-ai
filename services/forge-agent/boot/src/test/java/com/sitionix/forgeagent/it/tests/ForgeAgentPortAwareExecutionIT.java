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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.application.runtime.AgentExecutionResult;
import com.sitionix.forgeagent.application.runtime.AgentExecutor;
import com.sitionix.forgeagent.application.runtime.AgentSessionLeaseService;
import com.sitionix.forgeagent.application.runtime.AgentExecutionRecoveryService;
import com.sitionix.forgeagent.application.runtime.AgentExecutionRecoveryInspection;
import com.sitionix.forgeagent.application.runtime.ProviderTurnRecoveryResult;
import com.sitionix.forgeagent.application.runtime.NodeRunWorker;
import com.sitionix.forgeagent.application.runtime.AgentExecutionRecoveryInspector;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspaceResolver;
import com.sitionix.forgeagent.application.runtime.NodeExecutionClaim;
import com.sitionix.forgeagent.application.runtime.NodeRunCompletionPersistence;
import com.sitionix.forgeagent.application.runtime.NodeRunCompletionProcessor;
import com.sitionix.forgeagent.application.runtime.NodeRunCompletionWorker;
import com.sitionix.forgeagent.application.runtime.NodeRunLifecycle;
import com.sitionix.forgeagent.application.runtime.SelectedOutputRoutingPolicy;
import com.sitionix.forgeagent.application.runtime.WorkflowExecutionCoordinator;
import com.sitionix.forgeagent.application.usecase.AgentUseCases;
import com.sitionix.forgeagent.application.usecase.CancelWorkflowRunUseCase;
import com.sitionix.forgeagent.application.usecase.CreateProjectTaskCommand;
import com.sitionix.forgeagent.application.usecase.CreateWorkflowRunCommand;
import com.sitionix.forgeagent.application.usecase.SaveAgentCommand;
import com.sitionix.forgeagent.application.usecase.SaveWorkflowCommand;
import com.sitionix.forgeagent.application.usecase.ProjectTaskUseCases;
import com.sitionix.forgeagent.application.usecase.RetryRecoveredNodeRunUseCase;
import com.sitionix.forgeagent.application.usecase.WorkflowRunUseCases;
import com.sitionix.forgeagent.application.usecase.WorkflowUseCases;
import com.sitionix.forgeagent.domain.model.AgentModelSelection;
import com.sitionix.forgeagent.domain.model.AgentExecutionRecoveryClaim;
import com.sitionix.forgeagent.domain.model.AgentExecutionRecoveryDisposition;
import com.sitionix.forgeagent.domain.model.AgentExecutionRecoveryReconciliation;
import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryTerminalOutcome;
import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryState;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventAppendResult;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventCandidate;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventCaptureStatus;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventStatus;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventType;
import com.sitionix.forgeagent.domain.model.AgentExecutionSessionStatus;
import com.sitionix.forgeagent.domain.model.AgentExecutionTerminalOutcome;
import com.sitionix.forgeagent.domain.model.AgentExecutionTurnStatus;
import com.sitionix.forgeagent.domain.model.AgentSessionExecutionClaim;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.model.ConnectionResolutionType;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodeContextMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodePort;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.OperatorStopStatus;
import com.sitionix.forgeagent.domain.model.NodeScopeMode;
import com.sitionix.forgeagent.domain.model.ProjectTaskDetails;
import com.sitionix.forgeagent.domain.model.WorkflowConnection;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.ConnectionResolutionRepository;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import com.sitionix.forgeagent.domain.port.AgentExecutionEventRepository;
import com.sitionix.forgeagent.domain.port.ExecutionFrameRepository;
import com.sitionix.forgeagent.domain.port.InputActivationResolutionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeagent.it.DeterministicCodexRuntimePort;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectRepositoryEntity;
import com.sitionix.forgeagent.infrastructure.codex.LiveCodexRecoveryFixture;
import com.sitionix.forgeagent.infrastructure.codex.RecoveryDispatchFixture;
import com.sitionix.forgeagent.infrastructure.codex.UnreadCodexDispatchFixture;
import com.sitionix.forgeit.core.test.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.core.JdbcTemplate;

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
    private CancelWorkflowRunUseCase cancelWorkflowRun;
    @Autowired
    private RetryRecoveredNodeRunUseCase retryRecoveredNodeRun;
    @Autowired
    private NodeRunLifecycle lifecycle;
    @Autowired
    private com.sitionix.forgeagent.application.runtime.ManualNodeRunLifecycle manualLifecycle;
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
    private WorkflowRunGraphRepository graphRepository;
    @SpyBean
    private ConnectionResolutionRepository resolutionRepository;
    @Autowired
    private ExecutionFrameRepository frameRepository;
    @Autowired
    private InputActivationResolutionRepository activationResolutionRepository;
    @Autowired
    private AgentExecutionSessionRepository agentExecutionSessionRepository;
    @Autowired
    private AgentExecutionEventRepository agentExecutionEventRepository;
    @Autowired
    private DeterministicCodexRuntimePort codexRuntimePort;
    @Autowired
    private AgentSessionLeaseService agentSessionLeaseService;
    @Autowired
    private AgentExecutionRecoveryService recoveryService;
    @Autowired
    private ExecutionWorkspaceResolver workspaceResolver;
    @SpyBean
    private AgentExecutor agentExecutor;
    @SpyBean
    private AgentExecutionRecoveryInspector recoveryInspector;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired
    private com.sitionix.forgeagent.domain.port.AgentExecutionDispatchGuard dispatchGuard;

    @MockBean
    private OutputSelector outputSelector;

    private final java.util.Map<UUID, NodeExecutionClaim> sessionClaims = new ConcurrentHashMap<>();

    @AfterEach
    void removeRepositoryWorkspaceFixture() throws IOException {
        this.sessionClaims.clear();
        this.codexRuntimePort.ready();
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

    @Autowired
    private com.sitionix.forgeagent.application.usecase.ResetAgentExecutionContextUseCase resetContext;
    @Autowired
    private com.sitionix.forgeagent.application.usecase.RecoveredNodeRunRetryEligibilityService retryEligibility;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resetIdleContextPreservesHistoryAndNextNormalInvocationsUseNewThenReusableSession(final boolean iterationMode) {
        final var first = this.prepareIdleResetContext(iterationMode);
        final var old = this.agentExecutionSessionRepository.findByNodeRunId(first.nodeRunId()).orElseThrow();
        final var oldNode = this.jdbcTemplate.queryForMap("SELECT * FROM node_runs WHERE id=?", first.nodeRunId());
        final var events = this.agentExecutionEventRepository.findPage(first.agentSessionClaim().turnId(), 0, 200).orElseThrow();
        final var beforeRun = this.workflowRunRepository.findById(old.session().workflowRunId()).orElseThrow();
        this.resetContext.execute(old.session().id());
        final var retired = this.agentExecutionSessionRepository.findByNodeRunId(first.nodeRunId()).orElseThrow();
        assertThat(retired.session().contextResetAt()).isNotNull();
        assertThat(retired.session().status()).isEqualTo(AgentExecutionSessionStatus.IDLE);
        assertThat(retired.session().providerConversationId()).isEqualTo(old.session().providerConversationId());
        assertThat(retired.turn()).isEqualTo(old.turn());
        assertThat(this.jdbcTemplate.queryForMap("SELECT * FROM node_runs WHERE id=?", first.nodeRunId())).isEqualTo(oldNode);
        assertThat(this.agentExecutionEventRepository.findPage(first.agentSessionClaim().turnId(), 0, 200).orElseThrow()).isEqualTo(events);
        assertThat(this.workflowRunRepository.findById(old.session().workflowRunId()).orElseThrow()).isEqualTo(beforeRun);
        final var resetResult = this.resetContext.execute(old.session().id());
        assertThat(this.resetContext.execute(old.session().id())).isEqualTo(resetResult);
        assertThat(this.agentExecutionSessionRepository.findByWorkflowRunId(beforeRun.id()).stream()
                .filter(row -> row.session().sourceNodeId().equals(IMPLEMENTER))).hasSize(1);
        verifyNoInteractions(this.agentExecutor, this.recoveryInspector);

        final NodeRun second = this.allocateNextImplementer(beforeRun.id());
        final var allocated = this.agentExecutionSessionRepository.findByNodeRunId(second.id()).orElseThrow();
        assertThat(second.contextIterationId()).isEqualTo(old.session().contextIterationId());
        assertThat(allocated.session().id()).isNotEqualTo(old.session().id());
        assertThat(allocated.session().providerConversationId()).isNull();
        assertThat(allocated.turn().sequence()).isEqualTo(1);
        final var secondClaim = this.lifecycle.tryStart(second.id()).orElseThrow();
        assertThat(secondClaim.agentSessionClaim().providerConversationId()).isNull();
        assertThat(this.agentExecutionSessionRepository.findByNodeRunId(second.id()).orElseThrow().session().status())
                .isEqualTo(AgentExecutionSessionStatus.CREATING);
        this.finishResetTurn(secondClaim, "thread-reset-b");
        final var third = this.allocateNextImplementer(beforeRun.id());
        final var thirdClaim = this.lifecycle.tryStart(third.id()).orElseThrow();
        assertThat(thirdClaim.agentSessionClaim().sessionId()).isEqualTo(allocated.session().id());
        assertThat(thirdClaim.agentSessionClaim().providerConversationId()).isEqualTo("thread-reset-b");
        assertThat(this.agentExecutionSessionRepository.findByNodeRunId(third.id()).orElseThrow()).satisfies(row -> {
            assertThat(row.turn().sequence()).isEqualTo(2);
            assertThat(row.session().status()).isEqualTo(AgentExecutionSessionStatus.RESUMING);
        });
        assertThat(this.agentExecutionSessionRepository.findByNodeRunId(first.nodeRunId()).orElseThrow()).isEqualTo(retired);
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void resetAndAllocationSerializeBothOrderings(final boolean resetWins, final boolean iterationMode) throws Exception {
        final var first = this.prepareIdleResetContext(iterationMode);
        final var old = this.agentExecutionSessionRepository.findByNodeRunId(first.nodeRunId()).orElseThrow();
        final UUID runId = old.session().workflowRunId();
        final var locked = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final var blockerPid = new java.util.concurrent.atomic.AtomicInteger();
        final var allocated = new AtomicReference<NodeRun>();
        try (var workers = Executors.newFixedThreadPool(2)) {
            final var winner = workers.submit(() -> new org.springframework.transaction.support.TransactionTemplate(this.transactionManager)
                    .execute(status -> {
                        blockerPid.set(this.jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        if (resetWins) this.resetContext.execute(old.session().id());
                        else allocated.set(this.allocateResetRaceChild(first.nodeRunId()));
                        locked.countDown();
                        this.awaitLatch(release);
                        return true;
                    }));
            this.awaitLatch(locked);
            final var loser = workers.submit(() -> {
                if (resetWins) allocated.set(this.allocateResetRaceChild(first.nodeRunId()));
                else assertThatThrownBy(() -> this.resetContext.execute(old.session().id()))
                        .isInstanceOf(com.sitionix.forgeagent.domain.exception.ConflictException.class)
                        .extracting("code").isEqualTo("AGENT_CONTEXT_RESET_BUSY");
            });
            try {
                this.awaitRecoveryLockWait(blockerPid.get());
            } finally {
                release.countDown();
            }
            assertThat(winner.get(10, TimeUnit.SECONDS)).isTrue();
            loser.get(10, TimeUnit.SECONDS);
        }
        final var after = this.agentExecutionSessionRepository.findByNodeRunId(first.nodeRunId()).orElseThrow();
        final var next = this.agentExecutionSessionRepository.findByNodeRunId(allocated.get().id()).orElseThrow();
        assertThat(after.turn()).isEqualTo(old.turn());
        assertThat(next.turn().status()).isEqualTo(AgentExecutionTurnStatus.QUEUED);
        if (resetWins) {
            assertThat(after.session().contextResetAt()).isNotNull();
            assertThat(next.session().id()).isNotEqualTo(old.session().id());
            assertThat(next.turn().sequence()).isEqualTo(1);
        } else {
            assertThat(after.session().contextResetAt()).isNull();
            assertThat(next.session().id()).isEqualTo(old.session().id());
            assertThat(next.turn().sequence()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATING", "RESUMING", "ACTIVE", "FAILED", "CLOSED"})
    void resetUnsafeSessionFailsWithoutMutationOrProviderCalls(final String status) {
        final var first = this.prepareIdleResetContext();
        final UUID sessionId = first.agentSessionClaim().sessionId();
        new org.springframework.transaction.support.TransactionTemplate(this.transactionManager).executeWithoutResult(tx -> {
            if (List.of("CREATING", "RESUMING", "ACTIVE").contains(status)) {
                this.jdbcTemplate.update("UPDATE agent_execution_turns SET status='ACTIVE' WHERE id=?", first.agentSessionClaim().turnId());
                this.jdbcTemplate.update("UPDATE agent_execution_sessions SET status=?,active_node_run_id=?,lease_owner_id='busy',lease_expires_at=clock_timestamp()+INTERVAL '30 seconds' WHERE id=?",
                        status, first.nodeRunId(), sessionId);
            } else if ("CLOSED".equals(status)) {
                this.jdbcTemplate.update("UPDATE agent_execution_sessions SET status='CLOSED',terminal_outcome='CANCELLED',closed_at=clock_timestamp() WHERE id=?", sessionId);
            } else {
                this.jdbcTemplate.update("UPDATE agent_execution_sessions SET status=? WHERE id=?", status, sessionId);
            }
        });
        final var before = this.jdbcTemplate.queryForMap("SELECT * FROM agent_execution_sessions WHERE id=?", sessionId);
        assertThatThrownBy(() -> this.resetContext.execute(sessionId))
                .isInstanceOf(com.sitionix.forgeagent.domain.exception.ConflictException.class);
        assertThat(this.jdbcTemplate.queryForMap("SELECT * FROM agent_execution_sessions WHERE id=?", sessionId)).isEqualTo(before);
        verifyNoInteractions(this.agentExecutor, this.recoveryInspector);
    }

    @Test
    void resetFreshSessionIsNotAllowed() {
        this.seed();
        this.saveTerminalWorkflow();
        final var run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Fresh context."));
        final var claim = this.lifecycle.tryStart(this.onlyPending(run.id(), A).id()).orElseThrow();
        this.finishResetTurn(claim, "fresh-reset-rejected");
        final var before = this.agentExecutionSessionRepository.findByNodeRunId(claim.nodeRunId()).orElseThrow();
        assertThatThrownBy(() -> this.resetContext.execute(before.session().id()))
                .isInstanceOf(com.sitionix.forgeagent.domain.exception.ConflictException.class)
                .extracting("code").isEqualTo("AGENT_CONTEXT_RESET_NOT_ALLOWED");
        assertThat(this.agentExecutionSessionRepository.findByNodeRunId(claim.nodeRunId()).orElseThrow()).isEqualTo(before);
        verifyNoInteractions(this.agentExecutor, this.recoveryInspector);
    }

    @Test
    void recoveredReusableResetChangesResumeToRetryAndCompletesWithNewContext() {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final var old = this.expiredRecoveryExecution("0.157.0");
        doReturn(ProviderTurnRecoveryResult.terminal(ProviderTurnRecoveryTerminalOutcome.SUCCEEDED, "Completed."))
                .when(this.recoveryInspector).inspect(any());
        assertThat(this.recoveryService.reconcileExpired()).isEqualTo(1);
        final var oldNode = this.nodeRunRepository.findById(old.nodeRunId()).orElseThrow();
        final var run = this.workflowRunRepository.findById(oldNode.workflowRunId()).orElseThrow();
        assertThat(this.retryEligibility.evaluateAll(run).get(old.nodeRunId()).action().name()).isEqualTo("RESUME");
        this.resetContext.execute(old.sessionId());
        final var retired = this.agentExecutionSessionRepository.findByNodeRunId(old.nodeRunId()).orElseThrow();
        final var events = this.agentExecutionEventRepository.findPage(old.turnId(), 0, 200).orElseThrow();
        assertThat(this.retryEligibility.evaluateAll(run).get(old.nodeRunId()).action().name()).isEqualTo("RETRY");
        final var retry = this.retryRecoveredNodeRun.execute(run.id(), old.nodeRunId());
        final var child = this.nodeRunRepository.findById(retry.nodeRunId()).orElseThrow();
        assertThat(child.retryOfNodeRunId()).isEqualTo(old.nodeRunId());
        assertThat(child.contextMode()).isEqualTo(NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE);
        final var claim = this.lifecycle.tryStart(child.id()).orElseThrow();
        assertThat(claim.agentSessionClaim().sessionId()).isNotEqualTo(old.sessionId());
        assertThat(claim.agentSessionClaim().providerConversationId()).isNull();
        this.finishResetTurn(claim, "thread-reset-recovered-b");
        assertThat(this.workflowRunRepository.findById(run.id()).orElseThrow().status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
        assertThat(this.nodeRunRepository.findById(old.nodeRunId()).orElseThrow()).isEqualTo(oldNode);
        assertThat(this.agentExecutionSessionRepository.findByNodeRunId(old.nodeRunId()).orElseThrow()).isEqualTo(retired);
        assertThat(this.agentExecutionEventRepository.findPage(old.turnId(), 0, 200).orElseThrow()).isEqualTo(events);
    }

    private NodeExecutionClaim prepareIdleResetContext() {
        return this.prepareIdleResetContext(false);
    }

    private NodeExecutionClaim prepareIdleResetContext(final boolean iterationMode) {
        this.seed();
        if (iterationMode) this.saveIterationReviewerWorkflow(); else this.saveReusableReviewerWorkflow();
        when(this.outputSelector.selectOutput(any(), any(), any())).thenReturn(STRATEGY_PASS, CODE_RETURN, STRATEGY_PASS, CODE_RETURN);
        final var run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Reset context test."));
        final var first = this.lifecycle.tryStart(this.onlyPending(run.id(), IMPLEMENTER).id()).orElseThrow();
        this.finishResetTurn(first, "thread-reset-a");
        return first;
    }

    private void finishResetTurn(final NodeExecutionClaim claim, final String conversationId) {
        this.agentSessionLeaseService.persistConversation(claim.agentSessionClaim(), conversationId, "0.157.0");
        this.agentSessionLeaseService.persistTurn(claim.agentSessionClaim(), "turn-" + claim.nodeRunId());
        assertThat(this.agentExecutionEventRepository.activate(claim.agentSessionClaim())).isTrue();
        assertThat(this.agentExecutionEventRepository.append(claim.agentSessionClaim(),
                new AgentExecutionEventCandidate(AgentExecutionEventType.AGENT_MESSAGE, AgentExecutionEventStatus.COMPLETED,
                        null, "reset-history-" + claim.nodeRunId(), "{\"text\":\"Preserved answer\"}", Instant.now())))
                .isNotNull();
        assertThat(this.agentExecutionEventRepository.markComplete(claim.agentSessionClaim())).isTrue();
        this.lifecycle.succeed(claim.nodeRunId(), new AgentExecutionResult(new NodeRunOutput("{\"answer\":\"done\"}"), null), claim.agentSessionClaim());
    }

    private NodeRun allocateResetRaceChild(final UUID parentId) {
        final var target = this.nodeRunRepository.findById(parentId).orElseThrow();
        // Exercise the normal NodeRun persistence allocator directly; routing has separate transactions.
        return this.nodeRunRepository.saveAndFlush(new NodeRun(UUID.randomUUID(), target.workflowRunId(), target.sourceNodeId(), target.sourceAgentId(),
                target.agentName(), target.agentInstructions(), target.agentOutputSchema(), target.inputMode(), target.position(),
                target.executionFrameId(), target.enteredViaInputPortId(), target.activationFrameId(), null, null,
                NodeRunStatus.PENDING, null, null, target.executionModel(), Instant.now(), null, null,
                target.repositoryId(), target.contextMode(), target.contextTrackingVersion(), target.id(),
                target.contextGroupKey(), target.contextIterationId()));
    }

    private NodeRun allocateNextImplementer(final UUID runId) {
        this.complete(this.onlyPending(runId, STRATEGY), "{\"strategy\":\"approved\"}");
        this.complete(this.onlyPending(runId, CODE), "{\"feedback\":\"Continue\"}");
        return this.onlyPending(runId, IMPLEMENTER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @EnabledIfSystemProperty(named = "forge.codex.live-reset-e2e", matches = "true")
    void liveCodexResetStartsNewConversationAndLaterResumesOnlyNewSession(final boolean recoverFirst) throws Exception {
        this.seed();
        final String liveModel = System.getProperty("forge.codex.live-model", "gpt-5.6-sol");
        this.codexRuntimePort.readyWithModel(liveModel);
        this.agentUseCases.updateAgent(AGENT_A_ID, new SaveAgentCommand(
                "Agent A", "Return only the requested JSON object.",
                AgentOutputSchema.ofCanonicalJsonObject("""
                        {"type":"object","properties":{"answer":{"type":"string"}},
                         "required":["answer"],"additionalProperties":false}
                        """), new AgentModelSelection("codex", liveModel, null)));
        this.saveReusableReviewerWorkflow();
        when(this.outputSelector.selectOutput(any(), any(), any()))
                .thenReturn(STRATEGY_PASS, CODE_RETURN, STRATEGY_PASS, CODE_RETURN, STRATEGY_PASS, CODE_PASS);
        final var run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID,
                new CreateWorkflowRunCommand("Return a JSON answer for the context reset acceptance."));
        final var first = this.lifecycle.tryStart(this.onlyPending(run.id(), IMPLEMENTER).id()).orElseThrow();
        final var provider = new LiveCodexRecoveryFixture(first.executionWorkspace().cwd());
        if (recoverFirst) {
            this.executeRecordedResetTurn(provider, first, false);
            this.jdbcTemplate.update("UPDATE agent_execution_sessions SET lease_expires_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?",
                    first.agentSessionClaim().sessionId());
            final var restarted = new AgentExecutionRecoveryService(this.agentExecutionSessionRepository,
                    List.of(provider.inspector()), this.workflowRunRepository, this.workspaceResolver, this.nodeRunRepository,
                    this.completionProcessor, this.coordinator, Clock.systemUTC());
            assertThat(restarted.reconcileExpired()).isEqualTo(1);
            assertThat(this.retryEligibility.evaluateAll(this.workflowRunRepository.findById(run.id()).orElseThrow())
                    .get(first.nodeRunId()).action().name()).isEqualTo("RESUME");
        } else {
            this.executeRecordedResetTurn(provider, first);
        }
        final var old = this.agentExecutionSessionRepository.findByNodeRunId(first.nodeRunId()).orElseThrow();
        assertThat(old.session().status()).isEqualTo(AgentExecutionSessionStatus.IDLE);
        assertThat(old.session().providerConversationId()).isNotBlank();
        assertThat(old.session().providerVersion()).isEqualTo("0.157.0");
        final var oldNode = this.nodeRunRepository.findById(first.nodeRunId()).orElseThrow();
        final var oldEvents = this.agentExecutionEventRepository.findPage(first.agentSessionClaim().turnId(), 0, 200).orElseThrow();
        final var requestsBefore = provider.executionProcesses().stream().flatMap(process -> process.requests().stream()).toList();
        final var inspectionsBefore = provider.inspectionProcesses();
        this.resetContext.execute(old.session().id());
        assertThat(provider.inspectionProcesses()).isEqualTo(inspectionsBefore);
        assertThat(provider.executionProcesses().stream().flatMap(process -> process.requests().stream()).toList())
                .isEqualTo(requestsBefore);
        assertThat(provider.executionProcesses()).hasSize(1);
        final var retired = this.agentExecutionSessionRepository.findByNodeRunId(first.nodeRunId()).orElseThrow();
        assertThat(retired.session().contextResetAt()).isNotNull();
        assertThat(retired.turn()).isEqualTo(old.turn());
        assertThat(retired.session().providerConversationId()).isEqualTo(old.session().providerConversationId());
        verifyNoInteractions(this.agentExecutor, this.recoveryInspector);

        final NodeRun secondNode;
        if (recoverFirst) {
            assertThat(this.retryEligibility.evaluateAll(this.workflowRunRepository.findById(run.id()).orElseThrow())
                    .get(first.nodeRunId()).action().name()).isEqualTo("RETRY");
            final var retried = this.retryRecoveredNodeRun.execute(run.id(), first.nodeRunId());
            secondNode = this.nodeRunRepository.findById(retried.nodeRunId()).orElseThrow();
            assertThat(secondNode.retryOfNodeRunId()).isEqualTo(first.nodeRunId());
            // Only B and its later continuation route through reviewers in this recovery scenario.
            when(this.outputSelector.selectOutput(any(), any(), any()))
                    .thenReturn(STRATEGY_PASS, CODE_RETURN, STRATEGY_PASS, CODE_PASS);
        } else {
            secondNode = this.allocateNextImplementer(run.id());
        }
        final var secondAllocation = this.agentExecutionSessionRepository.findByNodeRunId(secondNode.id()).orElseThrow();
        assertThat(secondAllocation.session().id()).isNotEqualTo(old.session().id());
        assertThat(secondAllocation.session().providerConversationId()).isNull();
        assertThat(secondAllocation.turn().sequence()).isEqualTo(1);
        final var second = this.lifecycle.tryStart(secondNode.id()).orElseThrow();
        this.executeRecordedResetTurn(provider, second);
        final var newContext = this.agentExecutionSessionRepository.findByNodeRunId(secondNode.id()).orElseThrow();
        assertThat(newContext.session().providerConversationId()).isNotBlank().isNotEqualTo(old.session().providerConversationId());
        assertThat(provider.executionProcesses().get(1).requests()).extracting(request -> request.path("method").asText())
                .contains("thread/start", "turn/start").doesNotContain("thread/resume");
        assertThat(provider.executionProcesses().get(1).requests().toString()).doesNotContain(old.session().providerConversationId());

        final var thirdNode = this.allocateNextImplementer(run.id());
        final var third = this.lifecycle.tryStart(thirdNode.id()).orElseThrow();
        assertThat(third.agentSessionClaim().sessionId()).isEqualTo(newContext.session().id());
        assertThat(third.agentSessionClaim().providerConversationId()).isEqualTo(newContext.session().providerConversationId());
        this.executeRecordedResetTurn(provider, third);
        assertThat(this.agentExecutionSessionRepository.findByNodeRunId(thirdNode.id()).orElseThrow().turn().sequence()).isEqualTo(2);
        assertThat(provider.executionProcesses()).hasSize(3);
        assertThat(provider.executionProcesses().get(2).requests()).extracting(request -> request.path("method").asText())
                .contains("thread/resume", "turn/start").doesNotContain("thread/start");
        assertThat(provider.executionProcesses().get(2).requests().stream()
                .filter(request -> "thread/resume".equals(request.path("method").asText())))
                .singleElement().satisfies(request -> assertThat(request.path("params").path("threadId").asText())
                        .isEqualTo(newContext.session().providerConversationId()));
        assertThat(this.agentExecutionSessionRepository.findByNodeRunId(first.nodeRunId()).orElseThrow()).isEqualTo(retired);
        assertThat(this.nodeRunRepository.findById(first.nodeRunId()).orElseThrow()).isEqualTo(oldNode);
        assertThat(this.agentExecutionEventRepository.findPage(first.agentSessionClaim().turnId(), 0, 200).orElseThrow()).isEqualTo(oldEvents);
        this.complete(this.onlyPending(run.id(), STRATEGY), "{\"strategy\":\"approved\"}");
        this.complete(this.onlyPending(run.id(), CODE), "{\"code\":\"approved\"}");
        assertThat(this.workflowRunRepository.findById(run.id()).orElseThrow().status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
        System.out.printf("LIVE_RESET_ACCEPTANCE recovered=%s sessionA=%s sessionB=%s conversationA=%s conversationB=%s resetRequests=0 sequenceB=1,2 protocol=thread/start,thread/resume history=unchanged workflow=SUCCEEDED%n",
                recoverFirst, old.session().id(), newContext.session().id(), old.session().providerConversationId(),
                newContext.session().providerConversationId());
    }

    private void executeRecordedResetTurn(final LiveCodexRecoveryFixture provider,
                                          final NodeExecutionClaim claim) throws Exception {
        this.executeRecordedResetTurn(provider, claim, true);
    }

    private void executeRecordedResetTurn(final LiveCodexRecoveryFixture provider,
                                          final NodeExecutionClaim claim, final boolean complete) throws Exception {
        try (var heartbeat = Executors.newSingleThreadScheduledExecutor()) {
            final var renewal = heartbeat.scheduleAtFixedRate(
                    () -> this.agentSessionLeaseService.renew(claim.agentSessionClaim()),
                    AgentSessionLeaseService.HEARTBEAT_SECONDS, AgentSessionLeaseService.HEARTBEAT_SECONDS, TimeUnit.SECONDS);
            try {
                if (complete) {
                    final var output = provider.executeTrackedResumeTurn(claim, this.agentSessionLeaseService, this.agentExecutionEventRepository);
                    final var businessOutput = new NodeRunOutput(output);
                    final UUID selected = claim.availableOutputs().size() > 1
                            ? this.outputSelector.selectOutput(businessOutput, claim.availableOutputs(), claim.executionModel()) : null;
                    this.lifecycle.succeed(claim.nodeRunId(), new AgentExecutionResult(businessOutput, selected), claim.agentSessionClaim());
                } else {
                    provider.executeTrackedDurableTurn(claim, this.agentSessionLeaseService, this.agentExecutionEventRepository);
                }
            } finally {
                renewal.cancel(false);
                heartbeat.shutdownNow();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,REUSE_WITHIN_WORKFLOW_ITERATION", "true,REUSE_WITHIN_WORKFLOW_ITERATION", "false,SHARED_SESSION_GROUP", "true,SHARED_SESSION_GROUP"})
    void iterationFanInContinuesOneIdentityOrFailsClosed(final boolean conflict, final NodeContextMode mode) {
        this.seed();
        final var left = this.iterationNode(this.node(A, AGENT_A_ID, List.of(this.port(A_IN, "Input")), List.of(this.port(A_OUT, "Output")), 1), mode);
        final var rightBase = this.node(B, AGENT_B_ID, List.of(this.port(B_IN, "Input")), List.of(this.port(B_OUT, "Output")), 2);
        this.workflowUseCases.updateWorkflow(WORKFLOW_ID, new SaveWorkflowCommand("Iteration fan-in", List.of(
                this.node(X, AGENT_C_ID, List.of(this.port(X_IN, "Input")), List.of(this.port(X_OUT, "Output")), 0),
                left, conflict ? this.iterationNode(rightBase, mode) : rightBase,
                this.iterationNode(this.node(C, AGENT_C_ID, List.of(this.port(C_IN, "Join")), List.of(this.port(C_OUT, "Result")), 3), mode)),
                List.of(this.connection(1, X_OUT, A_IN), this.connection(2, X_OUT, B_IN),
                        this.connection(3, A_OUT, C_IN), this.connection(4, B_OUT, C_IN)), X_IN, C_OUT));
        final var run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Join inputs."));
        this.complete(this.onlyPending(run.id(), X), "{}");
        final var a = this.onlyPending(run.id(), A);
        final var b = this.onlyPending(run.id(), B);
        this.complete(a, "{}");
        this.complete(b, "{}");
        if (conflict) {
            assertThat(a.contextIterationId()).isNotEqualTo(b.contextIterationId());
            assertThat(this.nodeRuns(run.id(), C)).isEmpty();
            assertThat(this.agentExecutionSessionRepository.findByWorkflowRunId(run.id())).noneMatch(c -> C.equals(c.session().sourceNodeId()));
            assertThat(this.nodeRunRepository.findById(b.id()).orElseThrow().failure().code()).isEqualTo("AGENT_CONTEXT_ITERATION_CONFLICT");
            assertThat(this.workflowRunRepository.findById(run.id()).orElseThrow().status()).isEqualTo(WorkflowRunStatus.FAILED);
        } else {
            assertThat(this.onlyPending(run.id(), C).contextIterationId()).isEqualTo(a.contextIterationId());
            this.complete(this.onlyPending(run.id(), C), "{}");
            assertThat(this.workflowRunRepository.findById(run.id()).orElseThrow().status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
        }
        verifyNoInteractions(this.agentExecutor);
    }

    private Node iterationNode(final Node node) {
        return this.iterationNode(node, NodeContextMode.REUSE_WITHIN_WORKFLOW_ITERATION);
    }

    private Node iterationNode(final Node node, final NodeContextMode mode) {
        return new Node(node.id(), node.targetId(), node.inputMode(), node.inputs(), node.outputs(), node.position(),
                node.scopeMode(), mode, "implementation-review");
    }

    @ParameterizedTest
    @CsvSource({"false,REUSE_WITHIN_WORKFLOW_ITERATION", "true,REUSE_WITHIN_WORKFLOW_ITERATION", "false,SHARED_SESSION_GROUP", "true,SHARED_SESSION_GROUP"})
    void iterationRecoveryRetryPreservesConsumedInputsAndIdentity(final boolean reset, final NodeContextMode mode) {
        this.seed();
        this.saveIterationWorkflow(mode);
        final var run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Recover reviewer."));
        this.complete(this.onlyPending(run.id(), A), "{}");
        this.complete(this.onlyPending(run.id(), IMPLEMENTER), "{\"patch\":\"preserved\"}");
        final var parent = this.onlyPending(run.id(), STRATEGY);
        final var old = this.lifecycle.tryStart(parent.id()).orElseThrow();
        this.agentSessionLeaseService.persistConversation(old.agentSessionClaim(), "iteration-recovery-thread", "0.157.0");
        this.agentSessionLeaseService.persistTurn(old.agentSessionClaim(), "iteration-recovery-turn");
        this.jdbcTemplate.update("UPDATE agent_execution_sessions SET lease_expires_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?", old.agentSessionClaim().sessionId());
        doReturn(ProviderTurnRecoveryResult.terminal(ProviderTurnRecoveryTerminalOutcome.SUCCEEDED, "Completed"))
                .when(this.recoveryInspector).inspect(any());
        assertThat(this.recoveryService.reconcileExpired()).isEqualTo(1);
        final var failedRun = this.workflowRunRepository.findById(run.id()).orElseThrow();
        assertThat(this.retryEligibility.evaluateAll(failedRun).get(parent.id()).action().name()).isEqualTo("RESUME");
        if (reset) this.resetContext.execute(old.agentSessionClaim().sessionId());
        assertThat(this.retryEligibility.evaluateAll(failedRun).get(parent.id()).action().name()).isEqualTo(reset ? "RETRY" : "RESUME");
        final var oldNode = this.nodeRunRepository.findById(parent.id()).orElseThrow();
        final var childResult = this.retryRecoveredNodeRun.execute(run.id(), parent.id());
        final var child = this.nodeRunRepository.findById(childResult.nodeRunId()).orElseThrow();
        assertThat(child.contextIterationId()).isEqualTo(parent.contextIterationId());
        assertThat(child.contextGroupKey()).isEqualTo(parent.contextGroupKey());
        assertThat(child.retryOfNodeRunId()).isEqualTo(parent.id());
        final var claim = this.lifecycle.tryStart(child.id()).orElseThrow();
        assertThat(claim.inputEnvelope().contributions()).isEqualTo(old.inputEnvelope().contributions());
        if (reset) {
            assertThat(claim.agentSessionClaim().sessionId()).isNotEqualTo(old.agentSessionClaim().sessionId());
            assertThat(claim.agentSessionClaim().providerConversationId()).isNull();
            assertThat(this.agentExecutionSessionRepository.findByNodeRunId(child.id()).orElseThrow().turn().sequence()).isEqualTo(1);
        } else {
            assertThat(claim.agentSessionClaim().sessionId()).isEqualTo(old.agentSessionClaim().sessionId());
            assertThat(claim.agentSessionClaim().providerConversationId()).isEqualTo("iteration-recovery-thread");
            assertThat(this.agentExecutionSessionRepository.findByNodeRunId(child.id()).orElseThrow().turn().sequence()).isEqualTo(mode == NodeContextMode.SHARED_SESSION_GROUP ? 3 : 2);
        }
        assertThat(this.nodeRunRepository.findById(parent.id()).orElseThrow()).isEqualTo(oldNode);
    }

    @Test
    void iterationContextsAreIsolatedAcrossTwoEntriesWithFeedback() throws Exception {
        this.twoIterationWorkflow(false);
    }

    @Test
    @EnabledIfSystemProperty(named = "forge.codex.live-iteration-e2e", matches = "true")
    void liveCodexIsolatesTwoIndependentIterations() throws Exception {
        this.twoIterationWorkflow(true);
    }

    private void twoIterationWorkflow(final boolean live) throws Exception {
        this.seed();
        if (live) {
            final String model = System.getProperty("forge.codex.live-model", "gpt-5.6-sol");
            this.codexRuntimePort.readyWithModel(model);
            for (UUID agentId : List.of(AGENT_A_ID, AGENT_B_ID)) {
                this.agentUseCases.updateAgent(agentId, new SaveAgentCommand("Iteration agent " + agentId,
                        "Return the requested JSON answer. Do not use tools.", AgentOutputSchema.ofCanonicalJsonObject(
                        "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},\"required\":[\"answer\"],\"additionalProperties\":false}"),
                        new AgentModelSelection("codex", model, null)));
            }
        }
        this.saveIterationWorkflow();
        when(this.outputSelector.selectOutput(any(), any(), any()))
                .thenReturn(STRATEGY_RETURN, STRATEGY_PASS, B_OUT, STRATEGY_RETURN, STRATEGY_PASS, C_OUT);
        final var run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Return a JSON answer for iteration isolation."));
        // A template edit after snapshot must never change the running group.
        this.jdbcTemplate.update("UPDATE workflow_nodes SET context_group_key='future-group' WHERE context_group_key='implementation-review'");
        this.complete(this.onlyPending(run.id(), A), "{\"preparation\":1}");
        LiveCodexRecoveryFixture provider = null;
        final var invocations = new java.util.ArrayList<NodeRun>();
        for (int iteration = 0; iteration < 2; iteration++) {
            for (int turn = 0; turn < 2; turn++) {
                for (UUID source : List.of(IMPLEMENTER, STRATEGY)) {
                    final var candidates = this.pendingForSource(run.id(), source);
                    assertThat(candidates).as("iteration=%s turn=%s source=%s run=%s", iteration, turn, source, this.workflowRunRepository.findById(run.id())).hasSize(1);
                    final var node = candidates.getFirst();
                    invocations.add(node);
                    final var allocation = this.agentExecutionSessionRepository.findByNodeRunId(node.id()).orElseThrow();
                    assertThat(allocation.turn().sequence()).isEqualTo(turn + 1);
                    assertThat(allocation.session().contextIterationId()).isEqualTo(node.contextIterationId());
                    if (turn == 0) assertThat(allocation.session().providerConversationId()).isNull();
                    final var claim = this.lifecycle.tryStart(node.id()).orElseThrow();
                    if (live) {
                        if (provider == null) provider = new LiveCodexRecoveryFixture(claim.executionWorkspace().cwd());
                        this.executeRecordedResetTurn(provider, claim);
                        final var requests = provider.executionProcesses().getLast().requests();
                        assertThat(requests).extracting(request -> request.path("method").asText())
                                .contains(turn == 0 ? "thread/start" : "thread/resume")
                                .doesNotContain(turn == 0 ? "thread/resume" : "thread/start");
                        if (turn == 1) assertThat(requests.stream().filter(request -> "thread/resume".equals(request.path("method").asText())))
                                .singleElement().satisfies(request -> assertThat(request.path("params").path("threadId").asText())
                                        .isEqualTo(allocation.session().providerConversationId()));
                    } else {
                        if (turn == 0) this.agentSessionLeaseService.persistConversation(claim.agentSessionClaim(), "iteration-" + node.contextIterationId() + "-" + source, "0.157.0");
                        this.agentSessionLeaseService.persistTurn(claim.agentSessionClaim(), "turn-" + node.id());
                        UUID selected = source.equals(STRATEGY) ? this.outputSelector.selectOutput(new NodeRunOutput("{}"), claim.availableOutputs(), claim.executionModel()) : null;
                        this.lifecycle.succeed(node.id(), new AgentExecutionResult(new NodeRunOutput("{}"), selected), claim.agentSessionClaim());
                    }
                }
            }
            final var outside = this.onlyPending(run.id(), B);
            assertThat(outside.contextIterationId()).isNull();
            this.complete(outside, "{\"intermediate\":true}");
        }
        assertThat(this.workflowRunRepository.findById(run.id()).orElseThrow().status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
        for (int i = 0; i < 8; i++) {
            assertThat(invocations.get(i).contextGroupKey()).isEqualTo("implementation-review");
            assertThat(invocations.get(i).contextIterationId()).isEqualTo(invocations.get(i < 4 ? 0 : 4).contextIterationId());
        }
        assertThat(invocations.get(0).contextIterationId()).isNotEqualTo(invocations.get(4).contextIterationId());
        assertThat(invocations.get(0).executionFrameId()).isNotEqualTo(invocations.get(2).executionFrameId());
        assertThat(invocations.get(4).executionFrameId()).isNotEqualTo(invocations.get(6).executionFrameId());
        final var contexts = invocations.stream().map(node -> this.agentExecutionSessionRepository.findByNodeRunId(node.id()).orElseThrow()).toList();
        assertThat(contexts.get(0).session().id()).isEqualTo(contexts.get(2).session().id());
        assertThat(contexts.get(1).session().id()).isEqualTo(contexts.get(3).session().id());
        assertThat(contexts.get(4).session().id()).isEqualTo(contexts.get(6).session().id());
        assertThat(contexts.get(5).session().id()).isEqualTo(contexts.get(7).session().id());
        assertThat(List.of(contexts.get(0), contexts.get(1), contexts.get(4), contexts.get(5)))
                .extracting(c -> c.session().id()).doesNotHaveDuplicates();
        assertThat(List.of(contexts.get(0), contexts.get(1), contexts.get(4), contexts.get(5)))
                .extracting(c -> c.session().providerConversationId()).doesNotHaveDuplicates().doesNotContainNull();
        System.out.printf("ITERATION_ACCEPTANCE live=%s A=%s IA=%s RA=%s B=%s IB=%s RB=%s workflow=SUCCEEDED%n", live,
                invocations.get(0).contextIterationId(), contexts.get(0).session().providerConversationId(), contexts.get(1).session().providerConversationId(),
                invocations.get(4).contextIterationId(), contexts.get(4).session().providerConversationId(), contexts.get(5).session().providerConversationId());
    }

    private NodeRun allocateSharedInvocation(final UUID runId, final UUID sourceNode, final UUID iteration) {
        final var snapshot = this.graphRepository.findByWorkflowRunId(runId).nodes().stream()
                .filter(n -> n.sourceNodeId().equals(sourceNode)).findFirst().orElseThrow();
        final var frame = this.frameRepository.save(new com.sitionix.forgeagent.domain.model.ExecutionFrame(UUID.randomUUID(), runId, null, Instant.now()));
        return this.nodeRunRepository.saveAndFlush(new NodeRun(UUID.randomUUID(), runId, snapshot.sourceNodeId(), snapshot.sourceAgentId(),
                snapshot.agentName(), snapshot.agentInstructions(), snapshot.agentOutputSchema(), snapshot.inputMode(), snapshot.position(),
                frame.id(), null, null, null, null, NodeRunStatus.PENDING, null, null, snapshot.executionModel(), Instant.now(), null, null,
                null, snapshot.contextMode(), 1, null, snapshot.contextGroupKey(), iteration));
    }

    private WorkflowRun prepareSharedAllocationRun() {
        this.seed();
        this.saveIterationWorkflow(NodeContextMode.SHARED_SESSION_GROUP);
        return this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Shared allocation boundaries."));
    }

    @Test
    void sharedConcurrentFirstAllocationsCreateOneSessionAndOrderedTurns() throws Exception {
        final var run = this.prepareSharedAllocationRun();
        final var iteration = UUID.randomUUID();
        final var gate = new java.util.concurrent.CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            final var a = workers.submit(() -> { this.awaitLatch(gate); return this.allocateSharedInvocation(run.id(), IMPLEMENTER, iteration); });
            final var b = workers.submit(() -> { this.awaitLatch(gate); return this.allocateSharedInvocation(run.id(), STRATEGY, iteration); });
            gate.countDown();
            final var first = this.agentExecutionSessionRepository.findByNodeRunId(a.get(10, TimeUnit.SECONDS).id()).orElseThrow();
            final var second = this.agentExecutionSessionRepository.findByNodeRunId(b.get(10, TimeUnit.SECONDS).id()).orElseThrow();
            assertThat(first.session().id()).isEqualTo(second.session().id());
            assertThat(first.turn().id()).isNotEqualTo(second.turn().id());
            assertThat(List.of(first.turn().sequence(), second.turn().sequence())).containsExactlyInAnyOrder(1, 2);
            assertThat(this.jdbcTemplate.queryForObject("SELECT count(*) FROM agent_execution_sessions WHERE workflow_run_id=? AND context_iteration_id=?", Integer.class, run.id(), iteration)).isEqualTo(1);
            final var ordered = first.turn().sequence() == 1 ? first : second;
            final var later = first.turn().sequence() == 1 ? second : first;
            assertThat(this.agentExecutionSessionRepository.acquire(later.turn().nodeRunId(), "later")).isEmpty();
            assertThat(this.agentExecutionSessionRepository.acquire(ordered.turn().nodeRunId(), "first")).isPresent();
            assertThat(this.agentExecutionSessionRepository.acquire(later.turn().nodeRunId(), "later")).isEmpty();
            assertThat(this.agentExecutionSessionRepository.findSession(first.session().id()).orElseThrow().activeNodeRunId()).isEqualTo(ordered.turn().nodeRunId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sharedResetAndOtherNodeAllocationSerializeBothOrderings(final boolean resetWins) throws Exception {
        final var run = this.prepareSharedAllocationRun();
        final var iteration = UUID.randomUUID();
        final var first = this.allocateSharedInvocation(run.id(), IMPLEMENTER, iteration);
        final var claim = this.agentExecutionSessionRepository.acquire(first.id(), "setup").orElseThrow();
        this.agentExecutionSessionRepository.persistProviderConversation(claim.sessionId(), claim.leaseOwnerId(), claim.leaseToken(), "before-reset", "0.157.0");
        this.agentExecutionSessionRepository.finish(claim.sessionId(), claim.turnId(), claim.leaseOwnerId(), claim.leaseToken(), AgentExecutionTurnStatus.SUCCEEDED, null, null, false);
        final var locked = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final var blockerPid = new java.util.concurrent.atomic.AtomicInteger();
        final var allocated = new AtomicReference<NodeRun>();
        try (var workers = Executors.newFixedThreadPool(2)) {
            final var winner = workers.submit(() -> new org.springframework.transaction.support.TransactionTemplate(this.transactionManager).execute(status -> {
                blockerPid.set(this.jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                if (resetWins) this.resetContext.execute(claim.sessionId());
                else allocated.set(this.allocateSharedInvocation(run.id(), STRATEGY, iteration));
                locked.countDown();
                this.awaitLatch(release);
                return true;
            }));
            this.awaitLatch(locked);
            final var loser = workers.submit(() -> {
                if (resetWins) allocated.set(this.allocateSharedInvocation(run.id(), STRATEGY, iteration));
                else assertThatThrownBy(() -> this.resetContext.execute(claim.sessionId())).extracting("code").isEqualTo("AGENT_CONTEXT_RESET_BUSY");
            });
            try { this.awaitRecoveryLockWait(blockerPid.get()); } finally { release.countDown(); }
            assertThat(winner.get(10, TimeUnit.SECONDS)).isTrue();
            loser.get(10, TimeUnit.SECONDS);
        }
        final var next = this.agentExecutionSessionRepository.findByNodeRunId(allocated.get().id()).orElseThrow();
        assertThat(next.session().contextIterationId()).isEqualTo(iteration);
        assertThat(next.session().sourceNodeId()).isNull();
        assertThat(next.turn().sequence()).isEqualTo(resetWins ? 1 : 2);
        if (resetWins) {
            assertThat(next.session().id()).isNotEqualTo(claim.sessionId());
            assertThat(next.session().providerConversationId()).isNull();
            final var later = this.allocateSharedInvocation(run.id(), IMPLEMENTER, iteration);
            assertThat(this.agentExecutionSessionRepository.findByNodeRunId(later.id()).orElseThrow().session().id()).isEqualTo(next.session().id());
        } else assertThat(next.session().id()).isEqualTo(claim.sessionId());
        assertThat(this.agentExecutionSessionRepository.findByNodeRunId(first.id()).orElseThrow().turn().status()).isEqualTo(AgentExecutionTurnStatus.SUCCEEDED);
    }

    @Test
    void sharedStopCancelsActiveNodeBOnlyAndPreservesNodeAHistory() {
        this.seed();
        this.saveIterationWorkflow(NodeContextMode.SHARED_SESSION_GROUP);
        final var run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Stop shared B."));
        this.complete(this.onlyPending(run.id(), A), "{}");
        final var a = this.onlyPending(run.id(), IMPLEMENTER);
        this.complete(a, "{}");
        final var b = this.onlyPending(run.id(), STRATEGY);
        final var active = this.lifecycle.tryStart(b.id()).orElseThrow();
        this.agentSessionLeaseService.persistConversation(active.agentSessionClaim(), "stop-shared-thread", "0.157.0");
        this.agentSessionLeaseService.persistTurn(active.agentSessionClaim(), "stop-shared-turn-b");
        final var history = this.agentExecutionSessionRepository.findByNodeRunId(a.id()).orElseThrow().turn();
        assertThat(this.agentExecutionSessionRepository.findSession(active.agentSessionClaim().sessionId()).orElseThrow().activeNodeRunId()).isEqualTo(b.id());
        final var interrupted = new AtomicBoolean();
        when(this.agentExecutor.secureCancellation(b.id())).thenReturn(java.util.Optional.of(() -> interrupted.set(true)));
        this.cancelWorkflowRun.execute(run.id());
        assertThat(interrupted).isTrue();
        verify(this.agentExecutor).secureCancellation(b.id());
        verify(this.agentExecutor, never()).secureCancellation(a.id());
        assertThat(this.agentExecutionSessionRepository.findByNodeRunId(a.id()).orElseThrow().turn()).isEqualTo(history);
        assertThat(this.agentExecutionSessionRepository.findByNodeRunId(b.id()).orElseThrow().turn().status()).isEqualTo(AgentExecutionTurnStatus.CANCELLED);
        assertThat(this.nodeRunRepository.findById(a.id()).orElseThrow().status()).isEqualTo(NodeRunStatus.SUCCEEDED);
    }

    @Test
    void sharedContextsCrossAgentsAndIsolateIndependentEntries() throws Exception {
        this.sharedIterationWorkflow(false, false);
    }

    @Test
    @EnabledIfSystemProperty(named = "forge.codex.live-shared-e2e", matches = "true")
    void liveCodexSharedIterationsAndReset() throws Exception {
        this.sharedIterationWorkflow(true, true);
    }

    private void sharedIterationWorkflow(final boolean live, final boolean reset) throws Exception {
        this.seed();
        final String model = live ? System.getProperty("forge.codex.live-model", "gpt-5.6-sol") : "discovered-model";
        if (live) this.codexRuntimePort.readyWithModel(model);
        for (UUID agent : List.of(AGENT_A_ID, AGENT_B_ID)) {
            final String role = agent.equals(AGENT_A_ID) ? "implementer" : "reviewer";
            this.agentUseCases.updateAgent(agent, new SaveAgentCommand(role,
                    "You are the " + role + ". Return JSON with your role-specific field. Do not use tools.",
                    AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\",\"properties\":{\"" + role
                            + "\":{\"type\":\"string\"}},\"required\":[\"" + role + "\"],\"additionalProperties\":false}"),
                    new AgentModelSelection("codex", model, live ? null : "medium")));
        }
        this.saveIterationWorkflow(NodeContextMode.SHARED_SESSION_GROUP);
        when(this.outputSelector.selectOutput(any(), any(), any()))
                .thenReturn(STRATEGY_RETURN, STRATEGY_PASS, B_OUT, STRATEGY_RETURN, STRATEGY_PASS, C_OUT);
        final var run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Return role-specific JSON."));
        this.jdbcTemplate.update("UPDATE workflow_nodes SET context_group_key='future-group' WHERE context_group_key='implementation-review'");
        this.complete(this.onlyPending(run.id(), A), "{}");
        LiveCodexRecoveryFixture provider = null;
        final var nodes = new java.util.ArrayList<NodeRun>();
        final var firstSessions = new java.util.ArrayList<UUID>();
        final var conversations = new java.util.ArrayList<String>();
        for (int iteration = 0; iteration < 2; iteration++) {
            UUID sessionId = null;
            for (int index = 0; index < 4; index++) {
                final var source = index % 2 == 0 ? IMPLEMENTER : STRATEGY;
                final var node = this.onlyPending(run.id(), source);
                nodes.add(node);
                final var before = this.agentExecutionSessionRepository.findByNodeRunId(node.id()).orElseThrow();
                if (index == 0) { sessionId = before.session().id(); firstSessions.add(sessionId); }
                assertThat(before.session().id()).isEqualTo(sessionId);
                assertThat(before.session().sourceNodeId()).isNull();
                assertThat(before.session().sourceAgentId()).isNull();
                assertThat(before.session().contextGroupKey()).isEqualTo("implementation-review");
                assertThat(before.session().contextIterationId()).isEqualTo(node.contextIterationId());
                assertThat(before.turn().sequence()).isEqualTo(index + 1);
                final var claim = this.lifecycle.tryStart(node.id()).orElseThrow();
                final String role = index % 2 == 0 ? "implementer" : "reviewer";
                assertThat(claim.agentInstructions()).contains("You are the " + role);
                assertThat(claim.outputSchema().jsonObject()).contains("\"" + role + "\"");
                if (live) {
                    if (provider == null) provider = new LiveCodexRecoveryFixture(claim.executionWorkspace().cwd());
                    this.executeRecordedResetTurn(provider, claim);
                    final var requests = provider.executionProcesses().getLast().requests();
                    final String method = index == 0 ? "thread/start" : "thread/resume";
                    assertThat(requests).extracting(r -> r.path("method").asText()).contains(method)
                            .doesNotContain(index == 0 ? "thread/resume" : "thread/start");
                    assertThat(requests.stream().filter(r -> method.equals(r.path("method").asText())))
                            .singleElement().satisfies(r -> {
                                assertThat(r.path("params").path("developerInstructions").asText()).contains("You are the " + role);
                                if (before.session().providerConversationId() != null)
                                    assertThat(r.path("params").path("threadId").asText()).isEqualTo(before.session().providerConversationId());
                            });
                    assertThat(requests.stream().filter(r -> "turn/start".equals(r.path("method").asText())))
                            .singleElement().satisfies(r -> assertThat(r.path("params").path("outputSchema").toString()).contains(role));
                } else {
                    if (index == 0) this.agentSessionLeaseService.persistConversation(claim.agentSessionClaim(), "shared-" + node.contextIterationId(), "0.157.0");
                    this.agentSessionLeaseService.persistTurn(claim.agentSessionClaim(), "turn-" + node.id());
                    final UUID selected = source.equals(STRATEGY) ? this.outputSelector.selectOutput(new NodeRunOutput("{}"), claim.availableOutputs(), claim.executionModel()) : null;
                    this.lifecycle.succeed(node.id(), new AgentExecutionResult(new NodeRunOutput("{}"), selected), claim.agentSessionClaim());
                }
                final var after = this.agentExecutionSessionRepository.findByNodeRunId(node.id()).orElseThrow();
                if (index == 0) conversations.add(after.session().providerConversationId());
                assertThat(after.session().providerConversationId()).isEqualTo(conversations.get(iteration));
            }
            if (reset && iteration == 0) {
                final var original = this.agentExecutionSessionRepository.findByNodeRunId(nodes.getFirst().id()).orElseThrow();
                this.resetContext.execute(original.session().id());
                final var child = this.allocateResetRaceChild(nodes.getFirst().id());
                final var replacement = this.agentExecutionSessionRepository.findByNodeRunId(child.id()).orElseThrow();
                assertThat(child.contextIterationId()).isEqualTo(nodes.getFirst().contextIterationId());
                assertThat(replacement.session().id()).isNotEqualTo(original.session().id());
                assertThat(replacement.turn().sequence()).isEqualTo(1);
                final var claim = this.lifecycle.tryStart(child.id()).orElseThrow();
                this.executeRecordedResetTurn(provider, claim, false);
                final var requests = provider.executionProcesses().getLast().requests();
                assertThat(requests).extracting(r -> r.path("method").asText()).contains("thread/start").doesNotContain("thread/resume");
                final var replaced = this.agentExecutionSessionRepository.findByNodeRunId(child.id()).orElseThrow();
                assertThat(replaced.session().providerConversationId()).isNotEqualTo(conversations.getFirst());
                assertThat(this.agentExecutionSessionRepository.finish(claim.agentSessionClaim().sessionId(), claim.agentSessionClaim().turnId(),
                        claim.agentSessionClaim().leaseOwnerId(), claim.agentSessionClaim().leaseToken(), AgentExecutionTurnStatus.SUCCEEDED, null, null, false)).isTrue();
                // This extra allocator probe has no routing step: finish it without delivering another workflow edge.
                this.jdbcTemplate.update("UPDATE node_runs SET status='SUCCEEDED',finished_at=clock_timestamp(),routing_completed_at=clock_timestamp() WHERE id=?", child.id());
                System.out.printf("SHARED_RESET iteration=%s oldSession=%s replacement=%s oldThread=%s newThread=%s protocol=thread/start%n",
                        child.contextIterationId(), original.session().id(), replaced.session().id(), conversations.getFirst(), replaced.session().providerConversationId());
            }
            this.complete(this.onlyPending(run.id(), B), "{}");
        }
        assertThat(nodes.get(0).contextIterationId()).isNotEqualTo(nodes.get(4).contextIterationId());
        assertThat(nodes.subList(0, 4)).extracting(NodeRun::contextIterationId).containsOnly(nodes.getFirst().contextIterationId());
        assertThat(nodes.subList(4, 8)).extracting(NodeRun::contextIterationId).containsOnly(nodes.get(4).contextIterationId());
        assertThat(firstSessions).doesNotHaveDuplicates();
        assertThat(conversations).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(nodes.get(0).sourceAgentId()).isNotEqualTo(nodes.get(1).sourceAgentId());
        assertThat(this.workflowRunRepository.findById(run.id()).orElseThrow().status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
        System.out.printf("SHARED_ACCEPTANCE live=%s sessions=%s conversations=%s iterationA=%s iterationB=%s sequences=1,2,3,4 workflow=SUCCEEDED%n",
                live, firstSessions, conversations, nodes.get(0).contextIterationId(), nodes.get(4).contextIterationId());
    }

    private void saveIterationWorkflow() {
        this.saveIterationWorkflow(NodeContextMode.REUSE_WITHIN_WORKFLOW_ITERATION);
    }

    private void saveIterationWorkflow(final NodeContextMode mode) {
        this.workflowUseCases.updateWorkflow(WORKFLOW_ID, new SaveWorkflowCommand("Two independent iterations", List.of(
                this.node(A, AGENT_C_ID, List.of(this.port(A_IN, "Input")), List.of(this.port(A_OUT, "Prepared")), 0),
                new Node(IMPLEMENTER, AGENT_A_ID, NodeInputMode.DEPENDENCIES_ONLY,
                        List.of(this.port(IMPLEMENTER_INITIAL_IN, "Initial", 0), this.port(IMPLEMENTER_REVIEW_IN, "Feedback", 1), this.port(B_IN_UPDATED, "Next iteration", 2)),
                        List.of(this.port(IMPLEMENTER_OUT, "Implemented")), new NodePosition(250, 0), NodeScopeMode.GLOBAL,
                        mode, "implementation-review"),
                new Node(STRATEGY, AGENT_B_ID, NodeInputMode.DEPENDENCIES_ONLY, List.of(this.port(STRATEGY_IN, "Review")),
                        List.of(this.port(STRATEGY_PASS, "Approve", 0), this.port(STRATEGY_RETURN, "Reject", 1)),
                        new NodePosition(500, 0), NodeScopeMode.GLOBAL, mode, "implementation-review"),
                this.node(B, AGENT_C_ID, List.of(this.port(B_IN, "Intermediate")),
                        List.of(this.port(B_OUT, "Prepare next", 0), this.port(C_OUT, "Result", 1)), 3)),
                List.of(this.connection(1, A_OUT, IMPLEMENTER_INITIAL_IN), this.connection(2, IMPLEMENTER_OUT, STRATEGY_IN),
                        this.connection(3, STRATEGY_RETURN, IMPLEMENTER_REVIEW_IN), this.connection(4, STRATEGY_PASS, B_IN),
                        this.connection(5, B_OUT, B_IN_UPDATED)), A_IN, C_OUT));
    }

    @Test
    void reusableImplementerKeepsOneForgeSessionAcrossReviewedReentry() {
        this.seed();
        this.saveReusableReviewerWorkflow();
        when(this.outputSelector.selectOutput(any(), any(), any())).thenReturn(STRATEGY_PASS, CODE_RETURN);

        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(
                WORKFLOW_ID,
                new CreateWorkflowRunCommand("Implement feature with remembered fact cobalt-17.")
        );
        final NodeRun implementerOne = this.onlyPending(run.id(), IMPLEMENTER);
        final NodeExecutionClaim firstClaim = this.lifecycle.tryStart(implementerOne.id()).orElseThrow();
        this.agentSessionLeaseService.persistConversation(firstClaim.agentSessionClaim(), "thread-cobalt-17", "0.153.2");
        this.agentSessionLeaseService.persistTurn(firstClaim.agentSessionClaim(), "provider-turn-1");
        this.lifecycle.succeed(implementerOne.id(), this.result(implementerOne, "{\"patch\":\"v1\"}"), firstClaim.agentSessionClaim());

        this.complete(this.onlyPending(run.id(), STRATEGY), "{\"strategy\":\"ok\"}");
        this.complete(this.onlyPending(run.id(), CODE), "{\"code\":\"fix retry\"}");

        final NodeRun implementerTwo = this.nodeRuns(run.id(), IMPLEMENTER).get(1);
        final NodeExecutionClaim secondClaim = this.lifecycle.tryStart(implementerTwo.id()).orElseThrow();
        assertThat(secondClaim.agentSessionClaim().sessionId())
                .as("stored allocations: %s", this.agentExecutionSessionRepository.findByWorkflowRunId(run.id()))
                .isEqualTo(firstClaim.agentSessionClaim().sessionId());
        assertThat(secondClaim.agentSessionClaim().turnId()).isNotEqualTo(firstClaim.agentSessionClaim().turnId());
        assertThat(secondClaim.agentSessionClaim().providerConversationId()).isEqualTo("thread-cobalt-17");
        assertThat(secondClaim.inputEnvelope().contributions()).singleElement()
                .satisfies(contribution -> assertThat(contribution.payload())
                        .isEqualTo(new NodeRunOutput("{\"code\": \"fix retry\"}")));
        this.agentSessionLeaseService.persistTurn(secondClaim.agentSessionClaim(), "provider-turn-2");

        final var stored = this.agentExecutionSessionRepository.findByWorkflowRunId(run.id()).stream()
                .filter(allocation -> allocation.session().sourceNodeId().equals(IMPLEMENTER))
                .toList();
        assertThat(stored).hasSize(2);
        assertThat(stored).extracting(allocation -> allocation.session().id()).containsOnly(firstClaim.agentSessionClaim().sessionId());
        assertThat(stored).extracting(allocation -> allocation.session().providerConversationId()).containsOnly("thread-cobalt-17");
        assertThat(stored).extracting(allocation -> allocation.turn().id()).doesNotHaveDuplicates();
        assertThat(stored).extracting(allocation -> allocation.turn().providerTurnId())
                .containsExactlyInAnyOrder("provider-turn-1", "provider-turn-2");
    }

    @Test
    @EnabledIfSystemProperty(named = "forge.codex.live-session-e2e", matches = "true")
    void liveCodexResumesTheForgeImplementerSessionAcrossReviewerFeedback() {
        this.seed();
        final String fact = "forge-integrated-session-" + UUID.randomUUID();
        final String liveModel = System.getProperty("forge.codex.live-model", "gpt-5.6-sol");
        this.codexRuntimePort.readyWithModel(liveModel);
        this.agentUseCases.updateAgent(AGENT_A_ID, new SaveAgentCommand(
                "Agent A",
                "Remember private facts from the task. When review feedback asks for the fact, return it verbatim in JSON.",
                AgentOutputSchema.ofCanonicalJsonObject("""
                        {"type":"object","properties":{"answer":{"type":"string"}},
                         "required":["answer"],"additionalProperties":false}
                        """),
                new AgentModelSelection("codex", liveModel, null)
        ));
        this.saveReusableReviewerWorkflow();
        when(this.outputSelector.selectOutput(any(), any(), any())).thenReturn(STRATEGY_PASS, CODE_RETURN);

        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID,
                new CreateWorkflowRunCommand("Use the shell tool to run pwd exactly once. Then remember this private fact "
                        + "for the later review turn: " + fact));
        final NodeRun implementerOne = this.onlyPending(run.id(), IMPLEMENTER);
        final NodeExecutionClaim firstClaim = this.lifecycle.tryStart(implementerOne.id()).orElseThrow();
        final AgentExecutionResult firstResult = this.executeLiveWithHeartbeat(firstClaim);
        final var liveEvents = this.agentExecutionEventRepository.findPage(
                firstClaim.agentSessionClaim().turnId(), 0, 200).orElseThrow();
        assertThat(liveEvents.captureStatus()).isEqualTo(AgentExecutionEventCaptureStatus.COMPLETE);
        assertThat(liveEvents.events()).isNotEmpty()
                .allSatisfy(event -> assertThat(event.agentTurnId())
                        .isEqualTo(firstClaim.agentSessionClaim().turnId()));
        assertThat(liveEvents.events()).extracting(com.sitionix.forgeagent.domain.model.AgentExecutionEvent::sequence)
                .isSorted().doesNotHaveDuplicates();
        assertThat(liveEvents.events().getFirst()).satisfies(event -> {
            assertThat(event.type()).isEqualTo(AgentExecutionEventType.TURN);
            assertThat(event.status()).isEqualTo(AgentExecutionEventStatus.STARTED);
        });
        assertThat(liveEvents.events()).extracting(com.sitionix.forgeagent.domain.model.AgentExecutionEvent::type)
                .contains(AgentExecutionEventType.COMMAND, AgentExecutionEventType.AGENT_MESSAGE);
        assertThat(liveEvents.events().getLast()).satisfies(event -> {
            assertThat(event.type()).isEqualTo(AgentExecutionEventType.TURN);
            assertThat(event.status()).isEqualTo(AgentExecutionEventStatus.COMPLETED);
        });
        this.lifecycle.succeed(implementerOne.id(), firstResult, firstClaim.agentSessionClaim());

        this.complete(this.onlyPending(run.id(), STRATEGY), "{\"strategy\":\"approved\"}");
        this.complete(this.onlyPending(run.id(), CODE),
                "{\"feedback\":\"Return the exact private fact remembered during Implementer invocation #1.\"}");

        final NodeRun implementerTwo = this.nodeRuns(run.id(), IMPLEMENTER).get(1);
        final NodeExecutionClaim secondClaim = this.lifecycle.tryStart(implementerTwo.id()).orElseThrow();
        assertThat(secondClaim.inputEnvelope().contributions()).singleElement()
                .satisfies(contribution -> assertThat(contribution.payload().jsonValue()).contains("invocation #1"));
        final AgentExecutionResult secondResult = this.executeLiveWithHeartbeat(secondClaim);
        this.lifecycle.succeed(implementerTwo.id(), secondResult, secondClaim.agentSessionClaim());

        assertThat(secondResult.output().jsonValue()).contains(fact);
        assertThat(secondClaim.agentSessionClaim().sessionId()).isEqualTo(firstClaim.agentSessionClaim().sessionId());
        assertThat(secondClaim.agentSessionClaim().providerConversationId()).isNotBlank();
        final var implementerAllocations = this.agentExecutionSessionRepository.findByWorkflowRunId(run.id()).stream()
                .filter(allocation -> allocation.session().sourceNodeId().equals(IMPLEMENTER)).toList();
        assertThat(implementerAllocations).hasSize(2);
        assertThat(implementerAllocations).extracting(allocation -> allocation.session().providerConversationId())
                .containsOnly(secondClaim.agentSessionClaim().providerConversationId());
        assertThat(implementerAllocations).extracting(allocation -> allocation.turn().providerTurnId())
                .doesNotContainNull().doesNotHaveDuplicates();
        assertThat(this.agentExecutionSessionRepository.findByWorkflowRunId(run.id()).stream()
                .filter(allocation -> allocation.session().sourceNodeId().equals(CODE))
                .map(allocation -> allocation.session().id()))
                .doesNotContain(firstClaim.agentSessionClaim().sessionId());

        final WorkflowRun isolatedRun = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID,
                new CreateWorkflowRunCommand("An unrelated run must start clean."));
        final NodeExecutionClaim isolatedClaim = this.lifecycle.tryStart(
                this.onlyPending(isolatedRun.id(), IMPLEMENTER).id()).orElseThrow();
        assertThat(isolatedClaim.agentSessionClaim().sessionId()).isNotEqualTo(firstClaim.agentSessionClaim().sessionId());
        assertThat(isolatedClaim.agentSessionClaim().providerConversationId()).isNull();
    }

    @Test
    @EnabledIfSystemProperty(named = "forge.codex.live-recovery-e2e", matches = "true")
    void liveCodexRestartReconcilesPersistedExactTurnAndUnrelatedWorkflowStillSucceeds() throws Exception {
        this.seed();
        final String liveModel = System.getProperty("forge.codex.live-model", "gpt-5.6-sol");
        this.codexRuntimePort.readyWithModel(liveModel);
        this.agentUseCases.updateAgent(AGENT_A_ID, new SaveAgentCommand(
                "Agent A", "Return only the requested JSON object.",
                AgentOutputSchema.ofCanonicalJsonObject("""
                        {"type":"object","properties":{"answer":{"type":"string"}},
                         "required":["answer"],"additionalProperties":false}
                        """), new AgentModelSelection("codex", liveModel, null)));
        this.saveReusableTerminalWorkflow();
        final var orphanRun = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID,
                new CreateWorkflowRunCommand("Finish at the provider, then lose Forge ownership."));
        final var claim = this.lifecycle.tryStart(this.onlyPending(orphanRun.id(), A).id()).orElseThrow();
        final var provider = new LiveCodexRecoveryFixture(claim.executionWorkspace().cwd());
        try (var heartbeat = Executors.newSingleThreadScheduledExecutor()) {
            final var renewal = heartbeat.scheduleAtFixedRate(
                    () -> this.agentSessionLeaseService.renew(claim.agentSessionClaim()),
                    AgentSessionLeaseService.HEARTBEAT_SECONDS, AgentSessionLeaseService.HEARTBEAT_SECONDS,
                    TimeUnit.SECONDS);
            try {
                provider.executeTrackedDurableTurn(claim, this.agentSessionLeaseService, this.agentExecutionEventRepository);
            } finally {
                renewal.cancel(false);
                heartbeat.shutdownNow();
            }
        }

        final var beforeRecovery = this.agentExecutionSessionRepository.findByWorkflowRunId(orphanRun.id()).getFirst();
        assertThat(beforeRecovery.session().providerConversationId()).isNotBlank();
        assertThat(beforeRecovery.session().providerVersion()).isEqualTo("0.157.0");
        assertThat(beforeRecovery.turn().providerTurnId()).isNotBlank();
        assertThat(beforeRecovery.turn().status()).isEqualTo(AgentExecutionTurnStatus.ACTIVE);
        assertThat(beforeRecovery.turn().providerRecoveryState()).isNull();
        assertThat(this.nodeRunRepository.findById(claim.nodeRunId()).orElseThrow()).satisfies(node -> {
            assertThat(node.status()).isEqualTo(NodeRunStatus.RUNNING);
            assertThat(node.output()).isNull();
        });
        assertThat(this.agentExecutionEventRepository.findPage(claim.agentSessionClaim().turnId(), 0, 10)
                .orElseThrow().captureStatus()).isEqualTo(AgentExecutionEventCaptureStatus.ACTIVE);
        final var executionProcess = provider.executionProcesses().getFirst();
        assertThat(provider.executionProcesses()).hasSize(1);
        assertThat(executionProcess.isAlive()).isFalse();
        assertThat(executionProcess.requests().stream().filter(request -> "turn/start".equals(request.path("method").asText())))
                .hasSize(1);

        this.jdbcTemplate.update("UPDATE agent_execution_sessions SET lease_expires_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?",
                claim.agentSessionClaim().sessionId());
        final var freshInspector = provider.inspector();
        final AgentExecutionRecoveryInspector checkedInspector = new AgentExecutionRecoveryInspector() {
            @Override
            public boolean supports(final String providerId, final String version) {
                return freshInspector.supports(providerId, version);
            }

            @Override
            public ProviderTurnRecoveryResult inspect(final AgentExecutionRecoveryInspection inspection) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(executionProcess.isAlive()).isFalse();
                assertThat(inspection.providerConversationId()).isEqualTo(beforeRecovery.session().providerConversationId());
                assertThat(inspection.providerTurnId()).isEqualTo(beforeRecovery.turn().providerTurnId());
                return freshInspector.inspect(inspection);
            }
        };
        final var restartedRecovery = new AgentExecutionRecoveryService(this.agentExecutionSessionRepository,
                List.of(checkedInspector), this.workflowRunRepository, this.workspaceResolver, this.nodeRunRepository,
                this.completionProcessor, this.coordinator, Clock.systemUTC());
        assertThat(restartedRecovery.reconcileExpired()).isEqualTo(1);

        final var recovered = this.agentExecutionSessionRepository.findByWorkflowRunId(orphanRun.id()).getFirst();
        assertThat(recovered.turn().providerRecoveryState()).isEqualTo(ProviderTurnRecoveryState.TERMINAL);
        assertThat(recovered.turn().providerRecoveryTerminalOutcome()).isEqualTo(ProviderTurnRecoveryTerminalOutcome.SUCCEEDED);
        assertThat(recovered.turn().providerRecoveryCheckedAt()).isNotNull();
        assertThat(recovered.turn().status()).isEqualTo(AgentExecutionTurnStatus.FAILED);
        assertThat(recovered.turn().failureCode()).isEqualTo("AGENT_EXECUTION_RECOVERY_REQUIRED");
        assertThat(recovered.session().status()).isEqualTo(AgentExecutionSessionStatus.IDLE);
        assertThat(recovered.session().leaseOwnerId()).isNull();
        assertThat(recovered.session().providerConversationId()).isEqualTo(beforeRecovery.session().providerConversationId());
        assertThat(recovered.turn().providerTurnId()).isEqualTo(beforeRecovery.turn().providerTurnId());
        assertThat(this.nodeRunRepository.findById(claim.nodeRunId()).orElseThrow()).satisfies(node -> {
            assertThat(node.status()).isEqualTo(NodeRunStatus.FAILED);
            assertThat(node.failure().code()).isEqualTo("AGENT_EXECUTION_RECOVERY_REQUIRED");
            assertThat(node.output()).isNull();
        });
        assertThat(this.workflowRunRepository.findById(orphanRun.id()).orElseThrow()).satisfies(workflow -> {
            assertThat(workflow.status()).isEqualTo(WorkflowRunStatus.FAILED);
            assertThat(workflow.finishedAt()).isNotNull();
        });
        this.assertRecoveryCannotScheduleOrFabricateEvents(claim.agentSessionClaim());
        assertThat(provider.inspectionProcesses()).singleElement().satisfies(process -> {
            assertThat(process.pid()).isNotEqualTo(executionProcess.pid());
            assertThat(process.isAlive()).isFalse();
            assertThat(process.requests()).extracting(request -> request.path("method").asText())
                    .containsExactly("initialize", "initialized", "thread/turns/list");
            assertThat(process.requests()).allSatisfy(request -> assertThat(request.path("params").has("includeTurns")).isFalse());
            assertThat(process.requests().getLast().path("params").path("threadId").asText())
                    .isEqualTo(beforeRecovery.session().providerConversationId());
        });
        assertThat(restartedRecovery.reconcileExpired()).isZero();
        assertThat(provider.inspectionProcesses()).hasSize(1);
        verifyNoInteractions(this.agentExecutor);

        final var oldNodeBeforeResume = this.jdbcTemplate.queryForMap(
                "SELECT * FROM node_runs WHERE id=?", claim.nodeRunId());
        final var oldTurnBeforeResume = this.jdbcTemplate.queryForMap(
                "SELECT * FROM agent_execution_turns WHERE id=?", claim.agentSessionClaim().turnId());
        final var retry = this.retryRecoveredNodeRun.execute(orphanRun.id(), claim.nodeRunId());
        final NodeRun resumedNode = this.nodeRunRepository.findById(retry.nodeRunId()).orElseThrow();
        assertThat(resumedNode.retryOfNodeRunId()).isEqualTo(claim.nodeRunId());
        assertThat(retry.workflowRun().status()).isEqualTo(WorkflowRunStatus.RUNNING);
        final NodeExecutionClaim resumedClaim = this.lifecycle.tryStart(resumedNode.id()).orElseThrow();
        assertThat(resumedClaim.agentSessionClaim().sessionId()).isEqualTo(claim.agentSessionClaim().sessionId());
        assertThat(resumedClaim.agentSessionClaim().providerConversationId())
                .isEqualTo(beforeRecovery.session().providerConversationId());
        assertThat(resumedClaim.agentSessionClaim().turnId()).isNotEqualTo(claim.agentSessionClaim().turnId());
        assertThat(this.agentExecutionSessionRepository.findByNodeRunId(resumedNode.id()).orElseThrow()
                .turn().providerTurnId()).isNull();
        final String resumedOutput = provider.executeTrackedResumeTurn(
                resumedClaim, this.agentSessionLeaseService, this.agentExecutionEventRepository);
        this.lifecycle.succeed(resumedNode.id(), new AgentExecutionResult(
                new NodeRunOutput(resumedOutput), null), resumedClaim.agentSessionClaim());

        assertThat(this.jdbcTemplate.queryForMap("SELECT * FROM node_runs WHERE id=?", claim.nodeRunId()))
                .isEqualTo(oldNodeBeforeResume);
        assertThat(this.jdbcTemplate.queryForMap(
                "SELECT * FROM agent_execution_turns WHERE id=?", claim.agentSessionClaim().turnId()))
                .isEqualTo(oldTurnBeforeResume);
        final var completedResume = this.agentExecutionSessionRepository.findByNodeRunId(resumedNode.id()).orElseThrow();
        assertThat(completedResume.session().id()).isEqualTo(claim.agentSessionClaim().sessionId());
        assertThat(completedResume.session().providerConversationId())
                .isEqualTo(beforeRecovery.session().providerConversationId());
        assertThat(completedResume.turn().providerTurnId()).isNotBlank()
                .isNotEqualTo(beforeRecovery.turn().providerTurnId());
        assertThat(completedResume.turn().sequence()).isEqualTo(2);
        assertThat(provider.executionProcesses()).hasSize(2);
        assertThat(provider.executionProcesses().get(1).requests())
                .extracting(request -> request.path("method").asText())
                .contains("thread/resume", "turn/start")
                .doesNotContain("thread/start");
        assertThat(this.workflowRunRepository.findById(orphanRun.id()).orElseThrow()).satisfies(workflow -> {
            assertThat(workflow.status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
            assertThat(workflow.resultSourceNodeRunId()).isEqualTo(resumedNode.id());
        });
        assertThat(this.nodeRunRepository.findByWorkflowRunId(orphanRun.id()))
                .filteredOn(node -> node.routingCompletedAt() != null)
                .singleElement()
                .satisfies(node -> assertThat(node.id()).isEqualTo(resumedNode.id()));

        final var unrelatedRun = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID,
                new CreateWorkflowRunCommand("Return JSON with answer set to normal execution after restart."));
        final var unrelatedClaim = this.lifecycle.tryStart(this.onlyPending(unrelatedRun.id(), A).id()).orElseThrow();
        final var unrelatedResult = this.executeLiveWithHeartbeat(unrelatedClaim);
        this.lifecycle.succeed(unrelatedClaim.nodeRunId(), unrelatedResult, unrelatedClaim.agentSessionClaim());
        assertThat(this.workflowRunRepository.findById(unrelatedRun.id()).orElseThrow().status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
        assertThat(this.agentExecutionSessionRepository.findByWorkflowRunId(unrelatedRun.id()).getFirst()
                .session().providerConversationId()).isNotEqualTo(beforeRecovery.session().providerConversationId());
        assertThat(this.nodeRunRepository.findById(claim.nodeRunId()).orElseThrow().failure().code())
                .isEqualTo("AGENT_EXECUTION_RECOVERY_REQUIRED");
    }

    @Test
    @EnabledIfSystemProperty(named = "forge.codex.live-cancellation-e2e", matches = "true")
    void liveCodexActiveTurnIsStoppedAndAnUnrelatedRunStillCompletes() throws Exception {
        this.seed();
        final String liveModel = System.getProperty("forge.codex.live-model", "gpt-5.6-sol");
        this.codexRuntimePort.readyWithModel(liveModel);
        this.agentUseCases.updateAgent(AGENT_A_ID, new SaveAgentCommand(
                "Agent A",
                "Follow the requested shell command before returning the required JSON object.",
                AgentOutputSchema.ofCanonicalJsonObject("""
                        {"type":"object","properties":{"answer":{"type":"string"}},
                         "required":["answer"],"additionalProperties":false}
                        """),
                new AgentModelSelection("codex", liveModel, null)
        ));
        this.saveTerminalWorkflow();

        final WorkflowRun cancelledRun = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID,
                new CreateWorkflowRunCommand(
                        "Use the shell tool to run `sleep 30`, then inspect the current directory, then return JSON."));
        final NodeRun activeNode = this.onlyPending(cancelledRun.id(), A);
        final NodeExecutionClaim cancelledClaim = this.lifecycle.tryStart(activeNode.id()).orElseThrow();
        final CompletableFuture<AgentExecutionResult> execution = CompletableFuture.supplyAsync(
                () -> this.executeLiveWithHeartbeat(cancelledClaim));
        this.awaitLiveCommand(cancelledClaim, execution);

        this.cancelWorkflowRun.execute(cancelledRun.id());

        assertThatThrownBy(() -> execution.get(10, TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class);
        assertThat(this.workflowRunRepository.findById(cancelledRun.id()).orElseThrow()).satisfies(stopped -> {
            assertThat(stopped.status()).isEqualTo(WorkflowRunStatus.CANCELLED);
            assertThat(stopped.operatorStopStatus()).isEqualTo(OperatorStopStatus.COMPLETE);
            assertThat(stopped.operatorStopFailureCode()).isNull();
        });
        assertThat(this.nodeRunRepository.findByWorkflowRunId(cancelledRun.id()))
                .singleElement()
                .satisfies(nodeRun -> assertThat(nodeRun.status()).isEqualTo(NodeRunStatus.CANCELLED));
        final var allocation = this.agentExecutionSessionRepository.findByWorkflowRunId(cancelledRun.id())
                .getFirst();
        assertThat(allocation.turn().providerTurnId()).isNotBlank();
        assertThat(allocation.turn().status()).isEqualTo(AgentExecutionTurnStatus.CANCELLED);
        assertThat(allocation.session().status()).isEqualTo(AgentExecutionSessionStatus.CLOSED);
        assertThat(allocation.session().terminalOutcome()).isEqualTo(AgentExecutionTerminalOutcome.CANCELLED);
        assertThat(allocation.session().leaseOwnerId()).isNull();
        assertThat(allocation.session().leaseToken()).isGreaterThan(cancelledClaim.agentSessionClaim().leaseToken());
        final var interruptedEvents = this.agentExecutionEventRepository.findPage(
                cancelledClaim.agentSessionClaim().turnId(), 0, 200).orElseThrow();
        assertThat(interruptedEvents.captureStatus()).isEqualTo(AgentExecutionEventCaptureStatus.DEGRADED);
        assertThat(interruptedEvents.events()).noneSatisfy(event -> {
            assertThat(event.type()).isEqualTo(AgentExecutionEventType.TURN);
            assertThat(event.status()).isEqualTo(AgentExecutionEventStatus.FAILED);
        });
        final int recordedEventCount = interruptedEvents.events().size();
        Thread.sleep(500);
        assertThat(this.agentExecutionEventRepository.findPage(
                cancelledClaim.agentSessionClaim().turnId(), 0, 200).orElseThrow().events())
                .hasSize(recordedEventCount);

        final WorkflowRun unrelatedRun = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID,
                new CreateWorkflowRunCommand("Return JSON with answer set to normal execution."));
        final NodeExecutionClaim unrelatedClaim = this.lifecycle.tryStart(
                this.onlyPending(unrelatedRun.id(), A).id()).orElseThrow();
        final AgentExecutionResult unrelatedResult = this.executeLiveWithHeartbeat(unrelatedClaim);
        this.lifecycle.succeed(unrelatedClaim.nodeRunId(), unrelatedResult, unrelatedClaim.agentSessionClaim());
        assertThat(this.workflowRunRepository.findById(unrelatedRun.id()).orElseThrow().status())
                .isEqualTo(WorkflowRunStatus.SUCCEEDED);
    }

    @Test
    void concurrentClaimsForOneReusableSessionStartExactlyOneNodeRun() throws Exception {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(
                WORKFLOW_ID, new CreateWorkflowRunCommand("Run once."));
        final NodeRun pending = this.onlyPending(run.id(), A);

        try (var executor = Executors.newFixedThreadPool(2)) {
            final var first = executor.submit(() -> this.lifecycle.tryStart(pending.id()));
            final var second = executor.submit(() -> this.lifecycle.tryStart(pending.id()));
            assertThat(List.of(first.get(), second.get())).filteredOn(java.util.Optional::isPresent).hasSize(1);
        }
        assertThat(this.nodeRunRepository.findById(pending.id()).orElseThrow().status()).isEqualTo(NodeRunStatus.RUNNING);
    }

    @Test
    void recoveryClaimsOneDeterministicCandidateAndLeavesNormalOwnershipExpired() {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final AgentSessionExecutionClaim first = this.expiredRecoveryExecution();
        final AgentSessionExecutionClaim second = this.expiredRecoveryExecution();
        final UUID expectedSession = this.jdbcTemplate.queryForObject(
                "SELECT id FROM agent_execution_sessions ORDER BY workflow_run_id,id LIMIT 1", UUID.class);
        final var before = this.jdbcTemplate.queryForMap(
                "SELECT lease_owner_id,lease_token,lease_expires_at FROM agent_execution_sessions WHERE id=?", expectedSession);

        final AgentExecutionRecoveryClaim claim = this.agentExecutionSessionRepository.claimExpiredRecovery("recovery-a").orElseThrow();

        assertThat(claim.sessionId()).isEqualTo(expectedSession);
        assertThat(claim.ownerId()).isEqualTo("recovery-a");
        assertThat(claim.leaseToken()).isEqualTo(1);
        final var allocation = this.agentExecutionSessionRepository.findByNodeRunId(claim.nodeRunId()).orElseThrow();
        assertThat(claim.turnId()).isEqualTo(allocation.turn().id());
        assertThat(claim.workflowRunId()).isEqualTo(allocation.session().workflowRunId());
        assertThat(claim.repositoryId()).isEqualTo(allocation.session().repositoryId());
        assertThat(claim.contextMode()).isEqualTo(NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE);
        assertThat(claim.providerId()).isEqualTo("codex");
        assertThat(claim.providerVersion()).isEqualTo("0.153.2");
        assertThat(claim.providerConversationId()).isEqualTo("thread-" + claim.nodeRunId());
        assertThat(claim.providerTurnId()).isEqualTo("turn-" + claim.nodeRunId());
        assertThat(claim.nodeRunStatus()).isEqualTo(NodeRunStatus.RUNNING);
        assertThat(this.jdbcTemplate.queryForMap(
                "SELECT lease_owner_id,lease_token,lease_expires_at FROM agent_execution_sessions WHERE id=?", expectedSession)).isEqualTo(before);
        assertThat(this.jdbcTemplate.queryForObject(
                "SELECT recovery_lease_expires_at=updated_at+INTERVAL '30 seconds' FROM agent_execution_turns WHERE id=?", Boolean.class, claim.turnId())).isTrue();
        assertThat(claim.leaseExpiresAt()).isEqualTo(this.jdbcTemplate.queryForObject(
                "SELECT recovery_lease_expires_at FROM agent_execution_turns WHERE id=?", java.sql.Timestamp.class, claim.turnId()).toInstant());
        final var other = this.agentExecutionSessionRepository.claimExpiredRecovery("recovery-b").orElseThrow();
        assertThat(other.sessionId()).isNotEqualTo(claim.sessionId()).isIn(first.sessionId(), second.sessionId());
        assertThat(this.agentExecutionSessionRepository.claimExpiredRecovery("recovery-c")).isEmpty();
        assertThat(this.lifecycle.tryStart(claim.nodeRunId())).isEmpty();
    }

    @Test
    void recoveryThatWaitedForWorkflowLockCannotCommitAfterItsLeaseExpires() throws Exception {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final var normal = this.expiredRecoveryExecution();
        final var claim = this.agentExecutionSessionRepository.claimExpiredRecovery("waiting-recovery").orElseThrow();
        final var reconciliation = new AgentExecutionRecoveryReconciliation(
                AgentExecutionRecoveryDisposition.PROVIDER_UNKNOWN_FAIL_CLOSED, null,
                "AGENT_EXECUTION_RECOVERY_UNKNOWN", "Unknown provider state.");
        try (var worker = Executors.newSingleThreadExecutor()) {
            try (var blocker = this.jdbcTemplate.getDataSource().getConnection()) {
                blocker.setAutoCommit(false);
                try {
                    final int blockerPid = this.lockRecoveryWorkflow(blocker, claim.workflowRunId());
                    final var waiting = worker.submit(() -> this.agentExecutionSessionRepository.reconcileRecovery(claim, reconciliation));
                    this.awaitRecoveryLockWait(blockerPid);
                    // Expire after the waiting transaction started; no 30-second sleep is needed.
                    this.jdbcTemplate.update("UPDATE agent_execution_turns SET recovery_lease_expires_at=clock_timestamp() WHERE id=?", claim.turnId());
                    assertThat(this.jdbcTemplate.queryForObject("""
                            SELECT t.recovery_lease_expires_at>a.xact_start AND t.recovery_lease_expires_at<=clock_timestamp()
                              FROM agent_execution_turns t,pg_stat_activity a
                             WHERE t.id=? AND ?=ANY(pg_blocking_pids(a.pid))
                            """, Boolean.class, claim.turnId(), blockerPid)).isTrue();
                    final var nodeBefore = this.jdbcTemplate.queryForMap("SELECT * FROM node_runs WHERE id=?", normal.nodeRunId());
                    final var turnBefore = this.jdbcTemplate.queryForMap("SELECT * FROM agent_execution_turns WHERE id=?", normal.turnId());
                    final var sessionBefore = this.jdbcTemplate.queryForMap("SELECT * FROM agent_execution_sessions WHERE id=?", normal.sessionId());
                    blocker.commit();

                    assertThat(waiting.get(5, TimeUnit.SECONDS)).isFalse();
                    assertThat(this.jdbcTemplate.queryForMap("SELECT * FROM node_runs WHERE id=?", normal.nodeRunId())).isEqualTo(nodeBefore);
                    assertThat(this.jdbcTemplate.queryForMap("SELECT * FROM agent_execution_turns WHERE id=?", normal.turnId())).isEqualTo(turnBefore);
                    assertThat(this.jdbcTemplate.queryForMap("SELECT * FROM agent_execution_sessions WHERE id=?", normal.sessionId())).isEqualTo(sessionBefore);
                } finally {
                    blocker.rollback();
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"conversation", "turn", "renew", "lock", "eventActivate", "eventAppend", "eventComplete", "eventDegraded"})
    void recoveryClaimRejectsNormalCallbackFromTransactionOpenedBeforeExpiry(final String operation) throws Exception {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final var run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Fence stale callback."));
        final var normal = this.lifecycle.tryStart(this.onlyPending(run.id(), A).id()).orElseThrow().agentSessionClaim();
        if (operation.startsWith("event")) {
            this.agentSessionLeaseService.persistConversation(normal, "thread-before-recovery", "0.157.0");
            this.agentSessionLeaseService.persistTurn(normal, "turn-before-recovery");
            assertThat(this.agentExecutionEventRepository.activate(normal)).isTrue();
        }
        final var transactionStarted = new java.util.concurrent.CountDownLatch(1);
        final var resumeCallback = new java.util.concurrent.CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            final var callback = worker.submit(() -> new org.springframework.transaction.support.TransactionTemplate(this.transactionManager)
                    .execute(status -> {
                        this.jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP", java.sql.Timestamp.class);
                        transactionStarted.countDown();
                        this.awaitLatch(resumeCallback);
                        return this.normalCallback(normal, operation);
                    }));
            try {
                assertThat(transactionStarted.await(5, TimeUnit.SECONDS)).isTrue();
                this.jdbcTemplate.update("UPDATE agent_execution_sessions SET lease_expires_at=clock_timestamp() WHERE id=?", normal.sessionId());
                assertThat(this.agentExecutionSessionRepository.claimExpiredRecovery("recovery")).isPresent();
                final var sessionBefore = this.jdbcTemplate.queryForMap("SELECT * FROM agent_execution_sessions WHERE id=?", normal.sessionId());
                final var turnBefore = this.jdbcTemplate.queryForMap("SELECT * FROM agent_execution_turns WHERE id=?", normal.turnId());
                resumeCallback.countDown();

                assertThat(callback.get(5, TimeUnit.SECONDS)).isFalse();
                assertThat(this.jdbcTemplate.queryForMap("SELECT * FROM agent_execution_sessions WHERE id=?", normal.sessionId())).isEqualTo(sessionBefore);
                assertThat(this.jdbcTemplate.queryForMap("SELECT * FROM agent_execution_turns WHERE id=?", normal.turnId())).isEqualTo(turnBefore);
            } finally {
                resumeCallback.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"conversation", "turn", "renew", "lock", "eventActivate", "eventAppend", "eventComplete", "eventDegraded"})
    void recoveryOwnershipRejectsNormalCallbacksEvenWithFutureNormalExpiry(final String operation) {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final var normal = this.expiredRecoveryExecution();
        assertThat(this.agentExecutionSessionRepository.claimExpiredRecovery("recovery")).isPresent();
        this.jdbcTemplate.update("UPDATE agent_execution_sessions SET lease_expires_at=clock_timestamp()+INTERVAL '30 seconds' WHERE id=?", normal.sessionId());
        this.jdbcTemplate.update("UPDATE agent_execution_turns SET status='STARTING' WHERE id=?", normal.turnId());

        assertThat(this.normalCallback(normal, operation)).isFalse();
    }

    private boolean normalCallback(final AgentSessionExecutionClaim normal, final String operation) {
        return switch (operation) {
            case "conversation" -> this.agentExecutionSessionRepository.persistProviderConversation(
                    normal.sessionId(), normal.leaseOwnerId(), normal.leaseToken(), "late-thread", "0.157.0");
            case "turn" -> this.agentExecutionSessionRepository.persistProviderTurn(
                    normal.sessionId(), normal.turnId(), normal.leaseOwnerId(), normal.leaseToken(), "late-turn");
            case "renew" -> this.agentExecutionSessionRepository.renew(normal.sessionId(), normal.leaseOwnerId(), normal.leaseToken());
            case "lock" -> this.agentExecutionSessionRepository.lockCurrentLease(normal.sessionId(), normal.leaseOwnerId(), normal.leaseToken());
            case "eventActivate" -> this.agentExecutionEventRepository.activate(normal);
            case "eventAppend" -> this.agentExecutionEventRepository.append(normal, event(AgentExecutionEventType.WARNING, null, null)) == AgentExecutionEventAppendResult.APPENDED;
            case "eventComplete" -> this.agentExecutionEventRepository.markComplete(normal);
            case "eventDegraded" -> this.agentExecutionEventRepository.markDegraded(normal);
            default -> throw new IllegalArgumentException(operation);
        };
    }

    @Test
    void recoveryClaimBetweenConversationCommitAndWireDispatchPreventsTurnStart() throws Exception {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final var run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Claim before wire send."));
        final var claim = this.lifecycle.tryStart(this.onlyPending(run.id(), A).id()).orElseThrow();
        final var conversationPersisted = new java.util.concurrent.CountDownLatch(1);
        final var continueToSend = new java.util.concurrent.CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            try (var provider = new RecoveryDispatchFixture(this.agentSessionLeaseService, this.dispatchGuard,
                    () -> { conversationPersisted.countDown(); this.awaitLatch(continueToSend); }, () -> { })) {
                final var execution = worker.submit(() -> provider.execute(claim));
                try {
                    assertThat(conversationPersisted.await(5, TimeUnit.SECONDS)).isTrue();
                    this.jdbcTemplate.update("UPDATE agent_execution_sessions SET lease_expires_at=clock_timestamp() WHERE id=?", claim.agentSessionClaim().sessionId());
                    final var recovery = this.agentExecutionSessionRepository.claimExpiredRecovery("claim-wins").orElseThrow();
                    assertThat(recovery.providerConversationId()).isEqualTo("thread-fence");
                    assertThat(recovery.providerTurnId()).isNull();
                    continueToSend.countDown();
                    org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> execution.isDone() || provider.methods().contains("turn/start"));

                    assertThat(provider.methods()).contains("thread/start").doesNotContain("turn/start");
                    assertThatThrownBy(() -> execution.get(5, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class);
                    assertThat(this.agentExecutionSessionRepository.findByNodeRunId(claim.nodeRunId()).orElseThrow().turn().providerTurnId()).isNull();
                } finally {
                    continueToSend.countDown();
                }
            }
        }
    }

    @Test
    void recoverySkipsBusyWireDispatchThenFailsClosedOnNextPollWithoutWaitingForProviderReply() throws Exception {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final var run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Send before recovery claim."));
        final var claim = this.lifecycle.tryStart(this.onlyPending(run.id(), A).id()).orElseThrow();
        final var beforeWire = new java.util.concurrent.CountDownLatch(1);
        final var flushWire = new java.util.concurrent.CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            try (var provider = new RecoveryDispatchFixture(this.agentSessionLeaseService, this.dispatchGuard, () -> { },
                    () -> { beforeWire.countDown(); this.awaitLatch(flushWire); })) {
                final var execution = workers.submit(() -> provider.execute(claim));
                try {
                    assertThat(beforeWire.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(provider.turnWriteInTransaction()).isFalse();
                    this.jdbcTemplate.update("UPDATE agent_execution_sessions SET lease_expires_at=clock_timestamp() WHERE id=?", claim.agentSessionClaim().sessionId());
                    final var recovery = workers.submit(this.recoveryService::reconcileExpired);
                    assertThat(recovery.get(1, TimeUnit.SECONDS)).isZero();
                    flushWire.countDown();

                    org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> provider.methods().contains("turn/start"));
                    org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                            assertThat(this.recoveryService.reconcileExpired()).isEqualTo(1));
                    assertThat(provider.methods().stream().filter("turn/start"::equals)).hasSize(1);
                    assertThat(execution.isDone()).isFalse();
                    final var allocation = this.agentExecutionSessionRepository.findByNodeRunId(claim.nodeRunId()).orElseThrow();
                    assertThat(allocation.turn().providerTurnId()).isNull();
                    assertThat(allocation.turn().providerRecoveryState()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
                    assertThat(allocation.session().status()).isEqualTo(AgentExecutionSessionStatus.FAILED);
                    provider.replyTurn();
                    assertThatThrownBy(() -> execution.get(5, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class);
                } finally {
                    flushWire.countDown();
                }
            }
        }
    }

    @Test
    void concurrentDispatchesUseOneConnectionEachAndLeaveSmallPoolAvailableToLeaseWork() throws Exception {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final var firstRun = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("First dispatch."));
        final var first = this.lifecycle.tryStart(this.onlyPending(firstRun.id(), A).id()).orElseThrow().agentSessionClaim();
        final var secondRun = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Second dispatch."));
        final var second = this.lifecycle.tryStart(this.onlyPending(secondRun.id(), A).id()).orElseThrow().agentSessionClaim();
        final var source = this.jdbcTemplate.getDataSource().unwrap(com.zaxxer.hikari.HikariDataSource.class);
        final var config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(source.getJdbcUrl());
        config.setUsername(source.getUsername());
        config.setPassword(source.getPassword());
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(2000);
        config.addDataSourceProperty("ApplicationName", "forge-dispatch-small-pool");
        final var bothConnectionsHeld = new java.util.concurrent.CountDownLatch(2);
        final var beginLeaseChecks = new java.util.concurrent.CountDownLatch(1);
        final var checkouts = new java.util.concurrent.atomic.AtomicInteger();
        final var checkoutPid = new ThreadLocal<Integer>();
        final var sent = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = new com.zaxxer.hikari.HikariDataSource(config); var workers = Executors.newFixedThreadPool(4)) {
            final var limited = new org.springframework.jdbc.datasource.DelegatingDataSource(pool) {
                @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                    final var connection = super.getConnection();
                    if (checkouts.incrementAndGet() <= 2) {
                        bothConnectionsHeld.countDown();
                        ForgeAgentPortAwareExecutionIT.this.awaitLatch(beginLeaseChecks);
                    }
                    try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT pg_backend_pid()")) {
                        assertThat(row.next()).isTrue();
                        checkoutPid.set(row.getInt(1));
                    }
                    return connection;
                }
            };
            final var repositoryProxy = new org.springframework.aop.framework.ProxyFactory(
                    new com.sitionix.forgeagent.infrastructure.postgres.adapter.PostgresAgentExecutionSessionRepository(new JdbcTemplate(limited)));
            repositoryProxy.setProxyTargetClass(true);
            repositoryProxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(limited),
                    new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            final var sessions = (AgentExecutionSessionRepository) repositoryProxy.getProxy();
            final var guard = new com.sitionix.forgeagent.infrastructure.postgres.adapter.PostgresAgentExecutionDispatchGuard(limited);
            final Runnable write = () -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(this.jdbcTemplate.queryForObject("SELECT xact_start IS NULL FROM pg_stat_activity WHERE pid=?", Boolean.class, checkoutPid.get())).isTrue();
                sent.incrementAndGet();
            };
            final var firstSend = workers.submit(() -> guard.dispatch(first, write));
            final var secondSend = workers.submit(() -> guard.dispatch(second, write));
            try {
                assertThat(bothConnectionsHeld.await(5, TimeUnit.SECONDS)).isTrue();
                final var heartbeat = workers.submit(() -> sessions.renew(first.sessionId(), first.leaseOwnerId(), first.leaseToken()));
                final var recovery = workers.submit(() -> sessions.claimExpiredRecovery("small-pool-recovery"));
                beginLeaseChecks.countDown();

                firstSend.get(5, TimeUnit.SECONDS);
                secondSend.get(5, TimeUnit.SECONDS);
                assertThat(sent.get()).isEqualTo(2);
                assertThat(heartbeat.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(recovery.get(5, TimeUnit.SECONDS)).isEmpty();
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            } finally {
                beginLeaseChecks.countDown();
            }
        }
    }

    @Test
    void failedWireWriteReleasesAdvisoryOwnershipAndRunsOutsideAmbientTransaction() {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final var run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Release failed dispatch."));
        final var claim = this.lifecycle.tryStart(this.onlyPending(run.id(), A).id()).orElseThrow();

        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(this.transactionManager)
                .executeWithoutResult(status -> this.dispatchGuard.dispatch(claim.agentSessionClaim(), () -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    throw new IllegalStateException("Local write failed.");
                }))).isInstanceOf(IllegalStateException.class).hasMessage("Local write failed.");

        assertThat(this.jdbcTemplate.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND database=(SELECT oid FROM pg_database WHERE datname=current_database())", Integer.class)).isZero();
        this.jdbcTemplate.update("UPDATE agent_execution_sessions SET lease_expires_at=clock_timestamp() WHERE id=?", claim.agentSessionClaim().sessionId());
        assertThat(this.agentExecutionSessionRepository.claimExpiredRecovery("after-failed-write")).isPresent();
    }

    @Test
    void unreadProviderStdinTimesOutReleasesConnectionAndAllowsRecoveryWithoutDelayedSend() throws Exception {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final var run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Timeout unread provider stdin."));
        final var claim = this.lifecycle.tryStart(this.onlyPending(run.id(), A).id()).orElseThrow();
        try (var worker = Executors.newSingleThreadExecutor()) {
            try (var provider = new UnreadCodexDispatchFixture(this.agentSessionLeaseService, this.dispatchGuard)) {
                final var execution = worker.submit(() -> provider.execute(claim));
                assertThat(provider.awaitWrite()).isTrue();
                final var children = provider.process().descendants().toList();
                assertThat(children).isNotEmpty();
                assertThatThrownBy(() -> execution.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(RuntimeException.class);

                assertThat(provider.guardReleased()).isTrue();
                assertThat(provider.writeInTransaction()).isFalse();
                assertThat(provider.writeCompleted()).isFalse();
                assertThat(provider.process().isAlive()).isFalse();
                assertThat(children).noneMatch(ProcessHandle::isAlive);
                final var pool = this.jdbcTemplate.getDataSource().unwrap(com.zaxxer.hikari.HikariDataSource.class);
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                this.jdbcTemplate.update("UPDATE agent_execution_sessions SET lease_expires_at=clock_timestamp() WHERE id=?", claim.agentSessionClaim().sessionId());
                assertThat(this.recoveryService.reconcileExpired()).isEqualTo(1);
                final var allocation = this.agentExecutionSessionRepository.findByNodeRunId(claim.nodeRunId()).orElseThrow();
                assertThat(allocation.turn().providerTurnId()).isNull();
                assertThat(allocation.turn().providerRecoveryState()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
                assertThat(allocation.session().status()).isEqualTo(AgentExecutionSessionStatus.FAILED);
                assertThat(provider.writeCompleted()).isFalse();
            }
        }
    }

    private void awaitLatch(final java.util.concurrent.CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void recoveryClaimLeaseStartsAfterWaitingForWorkflowLock() throws Exception {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final var normal = this.expiredRecoveryExecution();
        final UUID workflowRunId = this.agentExecutionSessionRepository.findByNodeRunId(normal.nodeRunId()).orElseThrow().session().workflowRunId();
        try (var worker = Executors.newSingleThreadExecutor()) {
            try (var blocker = this.jdbcTemplate.getDataSource().getConnection()) {
                blocker.setAutoCommit(false);
                try {
                    final int blockerPid = this.lockRecoveryWorkflow(blocker, workflowRunId);
                    final var waiting = worker.submit(() -> this.agentExecutionSessionRepository.claimExpiredRecovery("waiting-claim"));
                    this.awaitRecoveryLockWait(blockerPid);
                    final Instant releaseTime = this.jdbcTemplate.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
                    blocker.commit();

                    final var claim = waiting.get(5, TimeUnit.SECONDS).orElseThrow();
                    assertThat(claim.leaseExpiresAt()).isAfter(releaseTime.plusSeconds(30));
                    assertThat(this.jdbcTemplate.queryForObject(
                            "SELECT recovery_lease_expires_at=updated_at+INTERVAL '30 seconds' FROM agent_execution_turns WHERE id=?",
                            Boolean.class, claim.turnId())).isTrue();
                } finally {
                    blocker.rollback();
                }
            }
        }
    }

    private int lockRecoveryWorkflow(final java.sql.Connection blocker, final UUID workflowRunId) throws java.sql.SQLException {
        try (var lock = blocker.prepareStatement("SELECT id FROM workflow_runs WHERE id=? FOR UPDATE")) {
            lock.setObject(1, workflowRunId);
            try (var result = lock.executeQuery()) {
                assertThat(result.next()).isTrue();
            }
        }
        try (var statement = blocker.createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private void awaitRecoveryLockWait(final int blockerPid) {
        org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(this.jdbcTemplate.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))",
                        Boolean.class, blockerPid)).isTrue());
    }

    @Test
    void recoveryRaceAndCrashAllowOnlyTheCurrentUnexpiredExactClaimToCommit() throws Exception {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final AgentSessionExecutionClaim normal = this.expiredRecoveryExecution();
        final AgentExecutionRecoveryClaim old;
        try (var workers = Executors.newFixedThreadPool(2)) {
            final var start = new java.util.concurrent.CountDownLatch(1);
            final var left = workers.submit(() -> { start.await(); return this.agentExecutionSessionRepository.claimExpiredRecovery("left"); });
            final var right = workers.submit(() -> { start.await(); return this.agentExecutionSessionRepository.claimExpiredRecovery("right"); });
            start.countDown();
            final var claims = java.util.stream.Stream.of(left.get(), right.get()).flatMap(java.util.Optional::stream).toList();
            assertThat(claims).hasSize(1);
            old = claims.getFirst();
        }
        final var reconciliation = new AgentExecutionRecoveryReconciliation(
                AgentExecutionRecoveryDisposition.PROVIDER_UNKNOWN_FAIL_CLOSED, null,
                "AGENT_EXECUTION_RECOVERY_UNKNOWN", "Provider state is unknown.");
        this.jdbcTemplate.update("UPDATE agent_execution_turns SET recovery_lease_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?", old.turnId());
        assertThat(this.agentExecutionSessionRepository.reconcileRecovery(old, reconciliation)).isFalse();
        final var current = this.agentExecutionSessionRepository.claimExpiredRecovery("replacement").orElseThrow();
        assertThat(current.leaseToken()).isEqualTo(old.leaseToken() + 1);
        assertThat(this.agentExecutionSessionRepository.reconcileRecovery(old, reconciliation)).isFalse();
        assertThat(this.agentExecutionSessionRepository.reconcileRecovery(
                this.recoveryFence(current, UUID.randomUUID(), current.ownerId(), current.leaseToken()), reconciliation)).isFalse();
        assertThat(this.agentExecutionSessionRepository.reconcileRecovery(
                this.recoveryFence(current, current.turnId(), "wrong-owner", current.leaseToken()), reconciliation)).isFalse();
        assertThat(this.agentExecutionSessionRepository.reconcileRecovery(
                this.recoveryFence(current, current.turnId(), current.ownerId(), current.leaseToken() + 1), reconciliation)).isFalse();
        assertThat(this.nodeRunRepository.findById(normal.nodeRunId()).orElseThrow().status()).isEqualTo(NodeRunStatus.RUNNING);
        assertThat(this.agentExecutionSessionRepository.reconcileRecovery(current, reconciliation)).isTrue();
        assertThat(this.agentExecutionSessionRepository.reconcileRecovery(current, reconciliation)).isFalse();
        assertThat(this.agentExecutionSessionRepository.findByNodeRunId(normal.nodeRunId()).orElseThrow().session().leaseToken())
                .isEqualTo(normal.leaseToken() + 1);
    }

    @ParameterizedTest
    @CsvSource({
            "PROVIDER_TERMINAL_RESULT_LOST,TERMINAL,SUCCEEDED,AGENT_EXECUTION_RECOVERY_REQUIRED,REUSE_WITHIN_WORKFLOW_NODE,IDLE",
            "PROVIDER_TERMINAL_RESULT_LOST,TERMINAL,FAILED,AGENT_EXECUTION_RECOVERY_REQUIRED,FRESH_EACH_NODE_RUN,CLOSED",
            "PROVIDER_ACTIVE_FAIL_CLOSED,ACTIVE,NULL,AGENT_EXECUTION_RECOVERY_PROVIDER_ACTIVE,REUSE_WITHIN_WORKFLOW_NODE,FAILED",
            "PROVIDER_ACTIVE_FAIL_CLOSED,ACTIVE,NULL,AGENT_EXECUTION_RECOVERY_PROVIDER_ACTIVE,FRESH_EACH_NODE_RUN,CLOSED",
            "PROVIDER_UNKNOWN_FAIL_CLOSED,UNKNOWN,NULL,AGENT_EXECUTION_RECOVERY_UNKNOWN,REUSE_WITHIN_WORKFLOW_NODE,FAILED",
            "PROVIDER_UNKNOWN_FAIL_CLOSED,UNKNOWN,NULL,AGENT_EXECUTION_RECOVERY_UNKNOWN,FRESH_EACH_NODE_RUN,CLOSED"
    })
    void recoveryDispositionsPersistEvidenceAndLifecycleWithoutFabricatedEvents(
            final String disposition, final String evidence, final String outcome, final String failureCode,
            final String contextMode, final String sessionStatus) {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final var normal = this.expiredRecoveryExecution();
        this.jdbcTemplate.update("UPDATE agent_execution_sessions SET context_mode=? WHERE id=?", contextMode, normal.sessionId());
        final var claim = this.agentExecutionSessionRepository.claimExpiredRecovery("recovery").orElseThrow();
        final var reconciliation = new AgentExecutionRecoveryReconciliation(AgentExecutionRecoveryDisposition.valueOf(disposition),
                "NULL".equals(outcome) ? null : ProviderTurnRecoveryTerminalOutcome.valueOf(outcome), failureCode, "Recovery diagnostic.");

        assertThat(this.agentExecutionSessionRepository.reconcileRecovery(claim, reconciliation)).isTrue();

        final var node = this.nodeRunRepository.findById(normal.nodeRunId()).orElseThrow();
        assertThat(node.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(node.failure().code()).isEqualTo(failureCode);
        final var allocation = this.agentExecutionSessionRepository.findByNodeRunId(normal.nodeRunId()).orElseThrow();
        assertThat(allocation.turn().status()).isEqualTo(AgentExecutionTurnStatus.FAILED);
        assertThat(allocation.turn().failureCode()).isEqualTo(failureCode);
        assertThat(allocation.turn().providerRecoveryState().name()).isEqualTo(evidence);
        assertThat(allocation.turn().providerRecoveryTerminalOutcome()).isEqualTo(reconciliation.providerTerminalOutcome());
        assertThat(allocation.turn().providerRecoveryCheckedAt()).isEqualTo(allocation.turn().updatedAt());
        assertThat(allocation.session().status().name()).isEqualTo(sessionStatus);
        assertThat(allocation.session().terminalOutcome()).isEqualTo("CLOSED".equals(sessionStatus) ? AgentExecutionTerminalOutcome.FAILED : null);
        assertThat(allocation.session().leaseOwnerId()).isNull();
        assertThat(allocation.session().leaseExpiresAt()).isNull();
        assertThat(allocation.session().activeNodeRunId()).isNull();
        assertThat(allocation.session().failureCode()).isEqualTo("TERMINAL".equals(evidence) ? null : failureCode);
        assertThat(allocation.session().closedAt()).satisfies(value -> {
            if ("CLOSED".equals(sessionStatus)) assertThat(value).isEqualTo(allocation.turn().providerRecoveryCheckedAt());
            else assertThat(value).isNull();
        });
        assertThat(this.jdbcTemplate.queryForMap("SELECT recovery_lease_owner_id,recovery_lease_expires_at FROM agent_execution_turns WHERE id=?", claim.turnId()))
                .hasEntrySatisfying("recovery_lease_owner_id", value -> assertThat(value).isNull())
                .hasEntrySatisfying("recovery_lease_expires_at", value -> assertThat(value).isNull());
        assertThat(this.agentExecutionEventRepository.findPage(claim.turnId(), 0, 10).orElseThrow()).satisfies(page -> {
            assertThat(page.captureStatus()).isEqualTo(AgentExecutionEventCaptureStatus.DEGRADED);
            assertThat(page.events()).isEmpty();
        });
    }

    @ParameterizedTest
    @CsvSource({"SUCCEEDED,NULL,IDLE", "FAILED,EXECUTION_FAILED,IDLE", "FAILED,AGENT_CONTEXT_PERSISTENCE_FAILED,FAILED", "CANCELLED,NULL,CLOSED", "BLOCKED,INPUT_UNAVAILABLE,IDLE"})
    void forgeTerminalRecoveryPreservesAllNodeHistoryAndHasNoProviderEvidence(
            final String status, final String failureCode, final String sessionStatus) {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final var normal = this.expiredRecoveryExecution();
        this.jdbcTemplate.update("UPDATE node_runs SET status=?,failure_code=?,failure_message='Persisted failure.',finished_at=CURRENT_TIMESTAMP-INTERVAL '1 hour' WHERE id=?",
                status, "NULL".equals(failureCode) ? null : failureCode, normal.nodeRunId());
        final var before = this.jdbcTemplate.queryForMap("SELECT * FROM node_runs WHERE id=?", normal.nodeRunId());
        final var claim = this.agentExecutionSessionRepository.claimExpiredRecovery("terminal-recovery").orElseThrow();
        assertThat(claim.nodeRunStatus().name()).isEqualTo(status);
        assertThat(claim.failureMessage()).isEqualTo("Persisted failure.");

        assertThat(this.agentExecutionSessionRepository.reconcileRecovery(claim, new AgentExecutionRecoveryReconciliation(
                AgentExecutionRecoveryDisposition.FORGE_TERMINAL, null, claim.failureCode(), claim.failureMessage()))).isTrue();

        assertThat(this.jdbcTemplate.queryForMap("SELECT * FROM node_runs WHERE id=?", normal.nodeRunId())).isEqualTo(before);
        final var allocation = this.agentExecutionSessionRepository.findByNodeRunId(normal.nodeRunId()).orElseThrow();
        assertThat(allocation.turn().status().name()).isEqualTo("BLOCKED".equals(status) ? "FAILED" : status);
        assertThat(allocation.turn().failureCode()).isEqualTo(claim.failureCode());
        assertThat(allocation.session().status().name()).isEqualTo(sessionStatus);
        assertThat(allocation.turn().providerRecoveryState()).isNull();
        assertThat(allocation.turn().providerRecoveryTerminalOutcome()).isNull();
        assertThat(allocation.turn().providerRecoveryCheckedAt()).isNull();
        assertThat(this.agentExecutionEventRepository.findPage(claim.turnId(), 0, 10).orElseThrow()).satisfies(page -> {
            assertThat(page.captureStatus()).isEqualTo(AgentExecutionEventCaptureStatus.DEGRADED);
            assertThat(page.events()).isEmpty();
        });
    }

    @Test
    void providerRecoveryCannotOverwriteForgeTruthCommittedAfterClaim() {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final var normal = this.expiredRecoveryExecution();
        final var claim = this.agentExecutionSessionRepository.claimExpiredRecovery("recovery").orElseThrow();
        this.jdbcTemplate.update("UPDATE node_runs SET status='SUCCEEDED',finished_at=CURRENT_TIMESTAMP WHERE id=?", normal.nodeRunId());
        final var nodeBefore = this.jdbcTemplate.queryForMap("SELECT * FROM node_runs WHERE id=?", normal.nodeRunId());
        final var turnBefore = this.jdbcTemplate.queryForMap("SELECT * FROM agent_execution_turns WHERE id=?", normal.turnId());
        final var sessionBefore = this.jdbcTemplate.queryForMap("SELECT * FROM agent_execution_sessions WHERE id=?", normal.sessionId());

        assertThat(this.agentExecutionSessionRepository.reconcileRecovery(claim, new AgentExecutionRecoveryReconciliation(
                AgentExecutionRecoveryDisposition.PROVIDER_UNKNOWN_FAIL_CLOSED, null,
                "AGENT_EXECUTION_RECOVERY_UNKNOWN", "Unknown provider state."))).isFalse();

        assertThat(this.jdbcTemplate.queryForMap("SELECT * FROM node_runs WHERE id=?", normal.nodeRunId())).isEqualTo(nodeBefore);
        assertThat(this.jdbcTemplate.queryForMap("SELECT * FROM agent_execution_turns WHERE id=?", normal.turnId())).isEqualTo(turnBefore);
        assertThat(this.jdbcTemplate.queryForMap("SELECT * FROM agent_execution_sessions WHERE id=?", normal.sessionId())).isEqualTo(sessionBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_STARTED", "COMPLETE", "DEGRADED", "NULL"})
    void fencedRecoveryPreservesNonActiveCaptureStates(final String captureStatus) {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final var normal = this.expiredRecoveryExecution();
        this.jdbcTemplate.update("UPDATE agent_execution_turns SET event_capture_status=? WHERE id=?",
                "NULL".equals(captureStatus) ? null : captureStatus, normal.turnId());
        final var claim = this.agentExecutionSessionRepository.claimExpiredRecovery("recovery").orElseThrow();

        assertThat(this.agentExecutionSessionRepository.reconcileRecovery(claim, new AgentExecutionRecoveryReconciliation(
                AgentExecutionRecoveryDisposition.PROVIDER_UNKNOWN_FAIL_CLOSED, null,
                "AGENT_EXECUTION_RECOVERY_UNKNOWN", "Unknown provider state."))).isTrue();

        assertThat(this.captureStatus(normal.turnId())).isEqualTo("NULL".equals(captureStatus) ? null : captureStatus);
    }

    @ParameterizedTest
    @CsvSource({
            "SUCCEEDED,REUSE_WITHIN_WORKFLOW_NODE,IDLE", "FAILED,REUSE_WITHIN_WORKFLOW_NODE,IDLE",
            "CANCELLED,REUSE_WITHIN_WORKFLOW_NODE,CLOSED", "SUCCEEDED,FRESH_EACH_NODE_RUN,CLOSED",
            "FAILED,FRESH_EACH_NODE_RUN,CLOSED", "CANCELLED,FRESH_EACH_NODE_RUN,CLOSED"
    })
    void applicationRecoveryPreservesTerminalForgeTruthWithoutInspectingProvider(
            final String status, final String contextMode, final String sessionStatus) {
        this.seed();
        this.saveRecoveryWorkflow(contextMode);
        final var normal = this.expiredRecoveryExecution("0.157.0");
        this.jdbcTemplate.update("""
                UPDATE node_runs SET status=?,output='{"authoritative":true}'::jsonb,
                    failure_code=?,failure_message=?,finished_at=CURRENT_TIMESTAMP-INTERVAL '1 hour'
                WHERE id=?
                """, status, "FAILED".equals(status) ? "EXECUTION_FAILED" : null,
                "FAILED".equals(status) ? "Persisted execution failure." : null, normal.nodeRunId());
        if ("SUCCEEDED".equals(status)) {
            this.jdbcTemplate.update(
                    "UPDATE node_runs SET routing_completed_at=CURRENT_TIMESTAMP-INTERVAL '30 minutes' WHERE id=?",
                    normal.nodeRunId());
        }
        final var before = this.jdbcTemplate.queryForMap("SELECT * FROM node_runs WHERE id=?", normal.nodeRunId());

        assertThat(this.recoveryService.reconcileExpired()).isEqualTo(1);

        verifyNoInteractions(this.recoveryInspector, this.agentExecutor);
        assertThat(this.jdbcTemplate.queryForMap("SELECT * FROM node_runs WHERE id=?", normal.nodeRunId())).isEqualTo(before);
        final var allocation = this.agentExecutionSessionRepository.findByNodeRunId(normal.nodeRunId()).orElseThrow();
        assertThat(allocation.turn().status().name()).isEqualTo(status);
        assertThat(allocation.turn().failureCode()).isEqualTo(before.get("failure_code"));
        assertThat(allocation.turn().failureMessage()).isEqualTo(before.get("failure_message"));
        assertThat(allocation.session().status().name()).isEqualTo(sessionStatus);
        assertThat(allocation.session().terminalOutcome()).isEqualTo("CLOSED".equals(sessionStatus)
                ? AgentExecutionTerminalOutcome.valueOf(status) : null);
        assertThat(allocation.turn().providerRecoveryState()).isNull();
        assertThat(allocation.turn().providerRecoveryTerminalOutcome()).isNull();
        assertThat(allocation.turn().providerRecoveryCheckedAt()).isNull();
        this.assertRecoveryCannotScheduleOrFabricateEvents(normal);
    }

    @ParameterizedTest
    @CsvSource({
            "TERMINAL,SHARED_SESSION_GROUP,IDLE,AGENT_EXECUTION_RECOVERY_REQUIRED",
            "ACTIVE,SHARED_SESSION_GROUP,FAILED,AGENT_EXECUTION_RECOVERY_PROVIDER_ACTIVE",
            "UNKNOWN,SHARED_SESSION_GROUP,FAILED,AGENT_EXECUTION_RECOVERY_UNKNOWN",
            "TERMINAL,REUSE_WITHIN_WORKFLOW_ITERATION,IDLE,AGENT_EXECUTION_RECOVERY_REQUIRED",
            "ACTIVE,REUSE_WITHIN_WORKFLOW_ITERATION,FAILED,AGENT_EXECUTION_RECOVERY_PROVIDER_ACTIVE",
            "UNKNOWN,REUSE_WITHIN_WORKFLOW_ITERATION,FAILED,AGENT_EXECUTION_RECOVERY_UNKNOWN",
            "TERMINAL,REUSE_WITHIN_WORKFLOW_NODE,IDLE,AGENT_EXECUTION_RECOVERY_REQUIRED",
            "TERMINAL,FRESH_EACH_NODE_RUN,CLOSED,AGENT_EXECUTION_RECOVERY_REQUIRED",
            "ACTIVE,REUSE_WITHIN_WORKFLOW_NODE,FAILED,AGENT_EXECUTION_RECOVERY_PROVIDER_ACTIVE",
            "ACTIVE,FRESH_EACH_NODE_RUN,CLOSED,AGENT_EXECUTION_RECOVERY_PROVIDER_ACTIVE",
            "UNKNOWN,REUSE_WITHIN_WORKFLOW_NODE,FAILED,AGENT_EXECUTION_RECOVERY_UNKNOWN",
            "UNKNOWN,FRESH_EACH_NODE_RUN,CLOSED,AGENT_EXECUTION_RECOVERY_UNKNOWN",
            "MISSING_THREAD,REUSE_WITHIN_WORKFLOW_NODE,FAILED,AGENT_EXECUTION_RECOVERY_UNKNOWN",
            "MISSING_THREAD,FRESH_EACH_NODE_RUN,CLOSED,AGENT_EXECUTION_RECOVERY_UNKNOWN",
            "MISSING_TURN,REUSE_WITHIN_WORKFLOW_NODE,FAILED,AGENT_EXECUTION_RECOVERY_UNKNOWN",
            "MISSING_TURN,FRESH_EACH_NODE_RUN,CLOSED,AGENT_EXECUTION_RECOVERY_UNKNOWN",
            "UNSUPPORTED_VERSION,REUSE_WITHIN_WORKFLOW_NODE,FAILED,AGENT_EXECUTION_RECOVERY_UNKNOWN",
            "UNSUPPORTED_VERSION,FRESH_EACH_NODE_RUN,CLOSED,AGENT_EXECUTION_RECOVERY_UNKNOWN"
    })
    void applicationRecoveryClassifiesExactPersistedTurnWithoutDuplicateExecution(
            final String scenario, final String contextMode, final String sessionStatus, final String failureCode) {
        this.seed();
        this.saveRecoveryWorkflow(contextMode);
        final var normal = this.expiredRecoveryExecution("UNSUPPORTED_VERSION".equals(scenario) ? "0.153.2" : "0.157.0");
        if ("MISSING_THREAD".equals(scenario)) {
            this.jdbcTemplate.update("UPDATE agent_execution_sessions SET provider_conversation_id=NULL WHERE id=?", normal.sessionId());
        } else if ("MISSING_TURN".equals(scenario)) {
            this.jdbcTemplate.update("UPDATE agent_execution_turns SET provider_turn_id=NULL WHERE id=?", normal.turnId());
        }
        final boolean inspectable = List.of("TERMINAL", "ACTIVE", "UNKNOWN").contains(scenario);
        doAnswer(invocation -> {
            final AgentExecutionRecoveryInspection inspection = invocation.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(inspection.providerId()).isEqualTo("codex");
            assertThat(inspection.providerVersion()).isEqualTo("0.157.0");
            assertThat(inspection.providerConversationId()).isEqualTo("thread-" + normal.nodeRunId());
            assertThat(inspection.providerTurnId()).isEqualTo("turn-" + normal.nodeRunId());
            assertThat(inspection.executionWorkspace().cwd()).isEqualTo(this.projectWorkspace());
            assertThat(this.nodeRunRepository.findById(normal.nodeRunId()).orElseThrow().status()).isEqualTo(NodeRunStatus.RUNNING);
            assertThat(this.nodeRunRepository.findPendingIds()).doesNotContain(normal.nodeRunId());
            assertThat(this.lifecycle.tryStart(normal.nodeRunId())).isEmpty();
            return switch (scenario) {
                case "TERMINAL" -> ProviderTurnRecoveryResult.terminal(ProviderTurnRecoveryTerminalOutcome.SUCCEEDED, "Exact completed turn.");
                case "ACTIVE" -> ProviderTurnRecoveryResult.active("Exact running turn.");
                default -> ProviderTurnRecoveryResult.unknown("No exact evidence.");
            };
        }).when(this.recoveryInspector).inspect(any());
        final Instant before = this.jdbcTemplate.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();

        assertThat(this.recoveryService.reconcileExpired()).isEqualTo(1);

        if (inspectable) verify(this.recoveryInspector).inspect(any());
        else verify(this.recoveryInspector, never()).inspect(any());
        verifyNoInteractions(this.agentExecutor);
        final var node = this.nodeRunRepository.findById(normal.nodeRunId()).orElseThrow();
        assertThat(node.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(node.failure().code()).isEqualTo(failureCode);
        assertThat(node.output()).isNull();
        assertThat(node.routingCompletedAt()).isNull();
        assertThat(this.nodeRunRepository.findByWorkflowRunId(node.workflowRunId())).hasSize(1);
        assertThat(this.workflowRunRepository.findById(node.workflowRunId()).orElseThrow()).satisfies(workflow -> {
            assertThat(workflow.status()).isEqualTo(WorkflowRunStatus.FAILED);
            assertThat(workflow.finishedAt()).isNotNull();
        });
        final var allocation = this.agentExecutionSessionRepository.findByNodeRunId(normal.nodeRunId()).orElseThrow();
        assertThat(allocation.turn().status()).isEqualTo(AgentExecutionTurnStatus.FAILED);
        assertThat(allocation.turn().failureCode()).isEqualTo(failureCode);
        assertThat(allocation.turn().failureMessage()).isEqualTo(node.failure().message());
        assertThat(allocation.turn().providerRecoveryState().name()).isEqualTo(inspectable ? scenario : "UNKNOWN");
        assertThat(allocation.turn().providerRecoveryTerminalOutcome()).isEqualTo("TERMINAL".equals(scenario)
                ? ProviderTurnRecoveryTerminalOutcome.SUCCEEDED : null);
        assertThat(allocation.turn().providerRecoveryCheckedAt()).isAfterOrEqualTo(before);
        assertThat(allocation.session().status().name()).isEqualTo(sessionStatus);
        assertThat(allocation.session().terminalOutcome()).isEqualTo("CLOSED".equals(sessionStatus) ? AgentExecutionTerminalOutcome.FAILED : null);
        assertThat(allocation.session().leaseOwnerId()).isNull();
        assertThat(allocation.session().activeNodeRunId()).isNull();
        if ("TERMINAL".equals(scenario)) {
            assertThat(node.failure().message()).isEqualTo("The provider turn is terminal, but Forge restarted before execution result/routing was committed.");
            assertThat(allocation.session().failureCode()).isNull();
        } else {
            assertThat(allocation.session().failureCode()).isEqualTo(failureCode);
        }
        this.assertRecoveryCannotScheduleOrFabricateEvents(normal);
        assertThat(this.recoveryService.reconcileExpired()).isZero();
    }

    @Test
    void terminalFreshRecoveryRetriesThroughANewSessionAndNormalWorkerCompletion() throws Exception {
        this.seed();
        this.saveTerminalWorkflow();
        final var oldClaim = this.expiredRecoveryExecution("0.157.0");
        final UUID workflowRunId = this.nodeRunRepository.findById(oldClaim.nodeRunId()).orElseThrow().workflowRunId();
        doReturn(ProviderTurnRecoveryResult.terminal(
                ProviderTurnRecoveryTerminalOutcome.SUCCEEDED, "Exact completed turn."))
                .when(this.recoveryInspector).inspect(any());
        assertThat(this.recoveryService.reconcileExpired()).isEqualTo(1);
        final var oldNodeBeforeRetry = this.jdbcTemplate.queryForMap(
                "SELECT * FROM node_runs WHERE id=?", oldClaim.nodeRunId());
        final var oldTurnBeforeRetry = this.jdbcTemplate.queryForMap(
                "SELECT * FROM agent_execution_turns WHERE id=?", oldClaim.turnId());

        final var retried = this.retryRecoveredNodeRun.execute(workflowRunId, oldClaim.nodeRunId());

        assertThat(retried.workflowRun().status()).isEqualTo(WorkflowRunStatus.RUNNING);
        assertThat(retried.workflowRun().finishedAt()).isNull();
        assertThat(this.jdbcTemplate.queryForMap("SELECT * FROM node_runs WHERE id=?", oldClaim.nodeRunId()))
                .isEqualTo(oldNodeBeforeRetry);
        assertThat(this.jdbcTemplate.queryForMap("SELECT * FROM agent_execution_turns WHERE id=?", oldClaim.turnId()))
                .isEqualTo(oldTurnBeforeRetry);
        final NodeRun retry = this.nodeRunRepository.findById(retried.nodeRunId()).orElseThrow();
        assertThat(retry.retryOfNodeRunId()).isEqualTo(oldClaim.nodeRunId());
        assertThat(retry.status()).isEqualTo(NodeRunStatus.PENDING);
        final var retryAllocation = this.agentExecutionSessionRepository.findByNodeRunId(retry.id()).orElseThrow();
        assertThat(retryAllocation.session().id()).isNotEqualTo(oldClaim.sessionId());
        assertThat(retryAllocation.session().providerConversationId()).isNull();
        assertThat(retryAllocation.turn().sequence()).isEqualTo(1);

        final AgentExecutor scheduledExecutor = mock(AgentExecutor.class);
        final AtomicReference<NodeExecutionClaim> executedRetry = new AtomicReference<>();
        when(scheduledExecutor.execute(any())).thenAnswer(invocation -> {
            final NodeExecutionClaim retryClaim = invocation.getArgument(0);
            if (retryClaim.nodeRunId().equals(retry.id())) {
                executedRetry.set(retryClaim);
            }
            return new AgentExecutionResult(new NodeRunOutput("{\"retried\":true}"), null);
        });
        try (var executor = Executors.newSingleThreadExecutor();
             var heartbeat = Executors.newSingleThreadScheduledExecutor()) {
            final var worker = new NodeRunWorker(this.nodeRunRepository, this.lifecycle, scheduledExecutor,
                    executor, heartbeat, this.agentSessionLeaseService, this.recoveryService, this.manualLifecycle);
            worker.poll();
            executor.submit(() -> { }).get(10, TimeUnit.SECONDS);
        }

        assertThat(this.nodeRunRepository.findById(oldClaim.nodeRunId()).orElseThrow().status())
                .isEqualTo(NodeRunStatus.FAILED);
        assertThat(this.nodeRunRepository.findById(retry.id()).orElseThrow()).satisfies(node -> {
            assertThat(node.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
            assertThat(node.routingCompletedAt()).isNotNull();
        });
        assertThat(this.workflowRunRepository.findById(workflowRunId).orElseThrow()).satisfies(run -> {
            assertThat(run.status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
            assertThat(run.result()).isEqualTo(new NodeRunOutput("{\"retried\": true}"));
            assertThat(run.resultSourceNodeRunId()).isEqualTo(retry.id());
        });
        assertThat(executedRetry.get()).isNotNull();
        assertThat(executedRetry.get().inputEnvelope().originalTask()).isEqualTo("Recover exact execution.");
        assertThat(executedRetry.get().inputEnvelope().contributions()).isEmpty();
    }

    @Test
    void nonRootRecoveryRetryPreservesExactSequentialInputEnvelopeAndParentHistory() {
        this.seed();
        this.saveLinearWorkflow();
        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(
                WORKFLOW_ID, new CreateWorkflowRunCommand("Sequential retry input."));
        this.complete(this.onlyPending(run.id(), A), "{\"from\":\"A\"}");
        final NodeExecutionClaim parentClaim = this.lifecycle.tryStart(this.onlyPending(run.id(), B).id()).orElseThrow();
        final List<ConnectionResolution> parentInputs = List.copyOf(
                this.resolutionRepository.findConsumedByNodeRunId(parentClaim.nodeRunId()));
        assertThat(parentInputs).singleElement();
        this.recoverTerminal(parentClaim);

        final var retried = this.retryRecoveredNodeRun.execute(run.id(), parentClaim.nodeRunId());

        assertThat(this.resolutionRepository.findConsumedByNodeRunId(parentClaim.nodeRunId()))
                .containsExactlyElementsOf(parentInputs);
        final List<ConnectionResolution> retryInputs =
                this.resolutionRepository.findConsumedByNodeRunId(retried.nodeRunId());
        this.assertRetryInputCopies(parentInputs, retryInputs, retried.nodeRunId());
        assertThat(this.lifecycle.tryStart(retried.nodeRunId()).orElseThrow().inputEnvelope())
                .isEqualTo(parentClaim.inputEnvelope());
    }

    @Test
    void consumedInputCopyFailureRollsBackRetryChildAllocationAndWorkflowReopen() {
        this.seed();
        this.saveLinearWorkflow();
        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(
                WORKFLOW_ID, new CreateWorkflowRunCommand("Rollback incomplete retry input."));
        this.complete(this.onlyPending(run.id(), A), "{\"from\":\"A\"}");
        final NodeExecutionClaim parentClaim = this.lifecycle.tryStart(this.onlyPending(run.id(), B).id()).orElseThrow();
        final List<ConnectionResolution> parentInputs = List.copyOf(
                this.resolutionRepository.findConsumedByNodeRunId(parentClaim.nodeRunId()));
        this.recoverTerminal(parentClaim);
        doThrow(new IllegalStateException("input copy failed"))
                .when(this.resolutionRepository).saveAll(any());

        assertThatThrownBy(() -> this.retryRecoveredNodeRun.execute(run.id(), parentClaim.nodeRunId()))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("input copy failed");

        assertThat(this.nodeRunRepository.findRetryChild(parentClaim.nodeRunId())).isEmpty();
        assertThat(this.nodeRunRepository.findByWorkflowRunId(run.id()))
                .noneMatch(nodeRun -> parentClaim.nodeRunId().equals(nodeRun.retryOfNodeRunId()));
        assertThat(this.workflowRunRepository.findById(run.id()).orElseThrow().status())
                .isEqualTo(WorkflowRunStatus.FAILED);
        assertThat(this.resolutionRepository.findConsumedByNodeRunId(parentClaim.nodeRunId()))
                .containsExactlyElementsOf(parentInputs);
    }

    @Test
    void fanInRecoveryRetryPreservesBothExactContributionsWithoutDuplication() {
        this.seed();
        this.saveDeepWorkflow(false);
        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(
                WORKFLOW_ID, new CreateWorkflowRunCommand("Fan-in retry input."));
        this.complete(this.onlyPending(run.id(), A), "{\"step\":\"A\"}");
        this.complete(this.onlyPending(run.id(), B), "{\"branch\":\"B\"}");
        this.complete(this.onlyPending(run.id(), C), "{\"branch\":\"C\"}");
        this.complete(this.onlyPending(run.id(), D), "{\"branch\":\"D\"}");
        final NodeExecutionClaim parentClaim = this.lifecycle.tryStart(this.onlyPending(run.id(), X).id()).orElseThrow();
        final List<ConnectionResolution> parentInputs = List.copyOf(
                this.resolutionRepository.findConsumedByNodeRunId(parentClaim.nodeRunId()));
        assertThat(parentInputs).hasSize(2);
        this.recoverTerminal(parentClaim);

        final var retried = this.retryRecoveredNodeRun.execute(run.id(), parentClaim.nodeRunId());

        assertThat(this.resolutionRepository.findConsumedByNodeRunId(parentClaim.nodeRunId()))
                .containsExactlyElementsOf(parentInputs);
        final List<ConnectionResolution> retryInputs =
                this.resolutionRepository.findConsumedByNodeRunId(retried.nodeRunId());
        this.assertRetryInputCopies(parentInputs, retryInputs, retried.nodeRunId());
        assertThat(this.lifecycle.tryStart(retried.nodeRunId()).orElseThrow().inputEnvelope())
                .isEqualTo(parentClaim.inputEnvelope());
    }

    @Test
    void reentryRecoveryRetryPreservesOnlyFailedLaterFrameInputs() {
        this.seed();
        this.saveReviewerWorkflow();
        when(this.outputSelector.selectOutput(any(), any(), any())).thenReturn(STRATEGY_PASS, CODE_RETURN);
        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(
                WORKFLOW_ID, new CreateWorkflowRunCommand("Re-entry retry input."));
        final NodeRun implementerOne = this.onlyPending(run.id(), IMPLEMENTER);
        this.complete(implementerOne, "{\"patch\":\"v1\"}");
        this.complete(this.onlyPending(run.id(), STRATEGY), "{\"strategy\":\"pass\"}");
        this.complete(this.onlyPending(run.id(), CODE), "{\"code\":\"return-v1\"}");
        final NodeExecutionClaim parentClaim = this.lifecycle.tryStart(
                this.nodeRuns(run.id(), IMPLEMENTER).get(1).id()).orElseThrow();
        final List<ConnectionResolution> parentInputs = List.copyOf(
                this.resolutionRepository.findConsumedByNodeRunId(parentClaim.nodeRunId()));
        assertThat(parentInputs).singleElement().satisfies(input -> {
            assertThat(input.executionFrameId()).isEqualTo(implementerOne.executionFrameId());
            assertThat(input.payload()).isEqualTo(new NodeRunOutput("{\"code\": \"return-v1\"}"));
        });
        this.recoverTerminal(parentClaim);

        final var retried = this.retryRecoveredNodeRun.execute(run.id(), parentClaim.nodeRunId());

        assertThat(this.resolutionRepository.findConsumedByNodeRunId(parentClaim.nodeRunId()))
                .containsExactlyElementsOf(parentInputs);
        final List<ConnectionResolution> retryInputs =
                this.resolutionRepository.findConsumedByNodeRunId(retried.nodeRunId());
        this.assertRetryInputCopies(parentInputs, retryInputs, retried.nodeRunId());
        assertThat(retryInputs).allSatisfy(input ->
                assertThat(input.executionFrameId()).isEqualTo(implementerOne.executionFrameId()));
        assertThat(this.lifecycle.tryStart(retried.nodeRunId()).orElseThrow().inputEnvelope())
                .isEqualTo(parentClaim.inputEnvelope());
    }

    @Test
    void terminalReusableRecoveryResumesSameSessionWithNextTurnAndIsIdempotentUnderRace() throws Exception {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final var oldClaim = this.expiredRecoveryExecution("0.157.0");
        final UUID workflowRunId = this.nodeRunRepository.findById(oldClaim.nodeRunId()).orElseThrow().workflowRunId();
        doReturn(ProviderTurnRecoveryResult.terminal(
                ProviderTurnRecoveryTerminalOutcome.SUCCEEDED, "Exact completed turn."))
                .when(this.recoveryInspector).inspect(any());
        assertThat(this.recoveryService.reconcileExpired()).isEqualTo(1);
        final var oldTurnBeforeRetry = this.jdbcTemplate.queryForMap(
                "SELECT * FROM agent_execution_turns WHERE id=?", oldClaim.turnId());

        final List<com.sitionix.forgeagent.application.usecase.RetryRecoveredNodeRunResult> results;
        try (var requests = Executors.newFixedThreadPool(2)) {
            final var start = new java.util.concurrent.CountDownLatch(1);
            final var left = requests.submit(() -> {
                start.await();
                return this.retryRecoveredNodeRun.execute(workflowRunId, oldClaim.nodeRunId());
            });
            final var right = requests.submit(() -> {
                start.await();
                return this.retryRecoveredNodeRun.execute(workflowRunId, oldClaim.nodeRunId());
            });
            start.countDown();
            results = List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
        }

        assertThat(results).extracting(
                com.sitionix.forgeagent.application.usecase.RetryRecoveredNodeRunResult::nodeRunId)
                .containsOnly(results.getFirst().nodeRunId());
        assertThat(this.nodeRunRepository.findByWorkflowRunId(workflowRunId))
                .filteredOn(node -> oldClaim.nodeRunId().equals(node.retryOfNodeRunId()))
                .singleElement();
        assertThat(this.jdbcTemplate.queryForMap("SELECT * FROM agent_execution_turns WHERE id=?", oldClaim.turnId()))
                .isEqualTo(oldTurnBeforeRetry);
        final var allocation = this.agentExecutionSessionRepository
                .findByNodeRunId(results.getFirst().nodeRunId()).orElseThrow();
        assertThat(allocation.session().id()).isEqualTo(oldClaim.sessionId());
        assertThat(allocation.session().providerConversationId()).isEqualTo("thread-" + oldClaim.nodeRunId());
        assertThat(allocation.session().status()).isEqualTo(AgentExecutionSessionStatus.IDLE);
        assertThat(allocation.turn().sequence()).isEqualTo(2);
        assertThat(allocation.turn().providerTurnId()).isNull();
        assertThat(allocation.turn().status()).isEqualTo(AgentExecutionTurnStatus.QUEUED);
    }

    @Test
    void forgeTerminalSuccessResumesPersistedOutputRoutingExactlyOnceAfterRecovery() {
        this.seed();
        this.saveLinearWorkflow();
        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(
                WORKFLOW_ID, new CreateWorkflowRunCommand("Resume routing after recovery."));
        final NodeExecutionClaim claim = this.lifecycle.tryStart(this.onlyPending(run.id(), A).id()).orElseThrow();
        final AgentSessionExecutionClaim session = claim.agentSessionClaim();
        this.agentSessionLeaseService.persistConversation(session, "thread-" + session.nodeRunId(), "0.157.0");
        this.agentSessionLeaseService.persistTurn(session, "turn-" + session.nodeRunId());
        assertThat(this.agentExecutionEventRepository.activate(session)).isTrue();
        this.jdbcTemplate.update("""
                UPDATE node_runs
                SET status='SUCCEEDED', output='{"step":"A"}'::jsonb, finished_at=clock_timestamp()
                WHERE id=?
                """, session.nodeRunId());
        this.jdbcTemplate.update("""
                UPDATE agent_execution_sessions
                SET lease_expires_at=clock_timestamp()-INTERVAL '1 second'
                WHERE id=?
                """, session.sessionId());

        assertThat(this.recoveryService.reconcileExpired()).isEqualTo(1);

        verifyNoInteractions(this.recoveryInspector, this.agentExecutor);
        final NodeRun routed = this.nodeRunRepository.findById(session.nodeRunId()).orElseThrow();
        assertThat(routed.status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(routed.output()).isEqualTo(new NodeRunOutput("{\"step\": \"A\"}"));
        assertThat(routed.routingCompletedAt()).isNotNull();
        assertThat(this.pendingForSource(run.id(), B)).singleElement();
        final UUID childId = this.onlyPending(run.id(), B).id();
        final Instant routedAt = routed.routingCompletedAt();

        assertThat(this.recoveryService.reconcileExpired()).isZero();
        this.completionProcessor.process(session.nodeRunId());

        assertThat(this.nodeRunRepository.findById(session.nodeRunId()).orElseThrow().routingCompletedAt())
                .isEqualTo(routedAt);
        assertThat(this.pendingForSource(run.id(), B)).singleElement()
                .satisfies(child -> assertThat(child.id()).isEqualTo(childId));
    }

    @Test
    void completionWorkerRetriesWorkflowReconciliationForStrandedFailedNodeRun() {
        this.seed();
        this.saveTerminalWorkflow();
        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(
                WORKFLOW_ID, new CreateWorkflowRunCommand("Retry stranded workflow completion."));
        final NodeRun running = this.start(this.onlyPending(run.id(), A));
        this.jdbcTemplate.update("""
                UPDATE node_runs
                SET status='FAILED', failure_code='RECOVERED_FAILURE',
                    failure_message='Recovery commit completed.', finished_at=clock_timestamp()
                WHERE id=?
                """, running.id());

        this.completionWorker.poll();

        assertThat(this.workflowRunRepository.findById(run.id()).orElseThrow()).satisfies(workflow -> {
            assertThat(workflow.status()).isEqualTo(WorkflowRunStatus.FAILED);
            assertThat(workflow.finishedAt()).isNotNull();
        });
        assertThat(this.nodeRunRepository.findByWorkflowRunId(run.id())).singleElement()
                .satisfies(nodeRun -> assertThat(nodeRun.failure().code()).isEqualTo("RECOVERED_FAILURE"));
    }

    @Test
    void slowProviderDeadlinePersistsUnknownBeforeRecoveryLeaseExpiresAndPreventsReclaim() {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final var normal = this.expiredRecoveryExecution("0.157.0");
        final var claimed = this.agentExecutionSessionRepository.claimExpiredRecovery("deadline-integration").orElseThrow();
        final Instant databaseNow = this.jdbcTemplate.queryForObject(
                "SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
        final Instant shortExpiry = databaseNow.plusSeconds(5);
        this.jdbcTemplate.update("UPDATE agent_execution_turns SET recovery_lease_expires_at=? WHERE id=?",
                java.sql.Timestamp.from(shortExpiry), claimed.turnId());
        final var shortClaim = this.withRecoveryLeaseExpiry(claimed, shortExpiry);
        final AgentExecutionSessionRepository deadlineRepository = mock(
                AgentExecutionSessionRepository.class,
                org.mockito.AdditionalAnswers.delegatesTo(this.agentExecutionSessionRepository));
        doReturn(Optional.of(shortClaim)).when(deadlineRepository).claimExpiredRecovery(any());
        final AgentExecutionRecoveryInspector deadlineInspector = mock(AgentExecutionRecoveryInspector.class);
        final var inspectionStarted = new java.util.concurrent.CountDownLatch(1);
        final var releaseInspection = new java.util.concurrent.CountDownLatch(1);
        final var inspectionExited = new java.util.concurrent.CountDownLatch(1);
        when(deadlineInspector.supports("codex", "0.157.0")).thenReturn(true);
        when(deadlineInspector.inspect(any())).thenAnswer(invocation -> {
            final AgentExecutionRecoveryInspection inspection = invocation.getArgument(0);
            assertThat(inspection.deadline()).isEqualTo(shortExpiry.minusSeconds(3));
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            inspectionStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    releaseInspection.await();
                    break;
                } catch (final InterruptedException exception) {
                    interrupted = true;
                }
            }
            inspectionExited.countDown();
            if (interrupted) Thread.currentThread().interrupt();
            return ProviderTurnRecoveryResult.terminal(
                    ProviderTurnRecoveryTerminalOutcome.SUCCEEDED, "Late result must be ignored.");
        });
        final var deadlineService = new AgentExecutionRecoveryService(
                deadlineRepository, List.of(deadlineInspector), this.workflowRunRepository,
                this.workspaceResolver, this.nodeRunRepository, this.completionProcessor, this.coordinator,
                Clock.systemUTC());

        try {
            assertThat(deadlineService.reconcileExpired()).isEqualTo(1);
            this.awaitLatch(inspectionStarted);
            assertThat(this.jdbcTemplate.queryForObject(
                    "SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant()).isBefore(shortExpiry);
            assertThat(this.agentExecutionSessionRepository.findByNodeRunId(normal.nodeRunId()).orElseThrow().turn()
                    .providerRecoveryState()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
            assertThat(this.agentExecutionSessionRepository.claimExpiredRecovery("deadline-reclaim")).isEmpty();
            verify(deadlineRepository).reconcileRecovery(eq(shortClaim), any());
        } finally {
            releaseInspection.countDown();
        }
        this.awaitLatch(inspectionExited);
        assertThat(this.agentExecutionSessionRepository.findByNodeRunId(normal.nodeRunId()).orElseThrow().turn()
                .providerRecoveryState()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
        verify(deadlineRepository).reconcileRecovery(eq(shortClaim), any());
    }

    @Test
    void crashedRecoveryIsReinspectedAndStaleApplicationResultCannotOverwriteReplacementWhileWorkerStaysUsable() throws Exception {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final var normal = this.expiredRecoveryExecution("0.157.0");
        final var inspected = new java.util.concurrent.CountDownLatch(1);
        final var releaseOldInspection = new java.util.concurrent.CountDownLatch(1);
        final var inspections = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            final AgentExecutionRecoveryInspection inspection = invocation.getArgument(0);
            assertThat(inspection.providerConversationId()).isEqualTo("thread-" + normal.nodeRunId());
            assertThat(inspection.providerTurnId()).isEqualTo("turn-" + normal.nodeRunId());
            if (inspections.incrementAndGet() == 1) {
                inspected.countDown();
                assertThat(releaseOldInspection.await(20, TimeUnit.SECONDS)).isTrue();
                return ProviderTurnRecoveryResult.terminal(ProviderTurnRecoveryTerminalOutcome.SUCCEEDED, "Delayed terminal evidence.");
            }
            return ProviderTurnRecoveryResult.active("Replacement observed exact active turn.");
        }).when(this.recoveryInspector).inspect(any());
        final AgentExecutor scheduledExecutor = mock(AgentExecutor.class);
        try (var recoverer = Executors.newSingleThreadExecutor();
             var executor = Executors.newSingleThreadExecutor();
             var heartbeat = Executors.newSingleThreadScheduledExecutor()) {
            final var worker = new NodeRunWorker(this.nodeRunRepository, this.lifecycle, scheduledExecutor,
                    executor, heartbeat, this.agentSessionLeaseService, this.recoveryService, this.manualLifecycle);
            final var oldRecovery = recoverer.submit(this.recoveryService::reconcileExpired);
            try {
                assertThat(inspected.await(10, TimeUnit.SECONDS)).isTrue();
                final var oldFence = this.jdbcTemplate.queryForMap("SELECT recovery_lease_owner_id,recovery_lease_token FROM agent_execution_turns WHERE id=?", normal.turnId());
                worker.poll();
                verifyNoInteractions(scheduledExecutor);
                assertThat(inspections).hasValue(1);
                assertThat(this.nodeRunRepository.findById(normal.nodeRunId()).orElseThrow().status()).isEqualTo(NodeRunStatus.RUNNING);
                assertThat(this.lifecycle.tryStart(normal.nodeRunId())).isEmpty();
                this.jdbcTemplate.update("UPDATE agent_execution_turns SET recovery_lease_expires_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?", normal.turnId());
                assertThat(this.recoveryService.reconcileExpired()).isEqualTo(1);
                assertThat(inspections).hasValue(2);
                assertThat(this.jdbcTemplate.queryForObject("SELECT recovery_lease_token FROM agent_execution_turns WHERE id=?", Long.class, normal.turnId()))
                        .isEqualTo(((Number) oldFence.get("recovery_lease_token")).longValue() + 1);
                final var reconciled = this.agentExecutionSessionRepository.findByNodeRunId(normal.nodeRunId()).orElseThrow();
                final var reconciledNode = this.jdbcTemplate.queryForMap("SELECT * FROM node_runs WHERE id=?", normal.nodeRunId());
                assertThat(reconciled.turn().providerRecoveryState().name()).isEqualTo("ACTIVE");
                releaseOldInspection.countDown();
                assertThat(oldRecovery.get(10, TimeUnit.SECONDS)).isZero();
                assertThat(this.agentExecutionSessionRepository.findByNodeRunId(normal.nodeRunId()).orElseThrow()).isEqualTo(reconciled);
                assertThat(this.jdbcTemplate.queryForMap("SELECT * FROM node_runs WHERE id=?", normal.nodeRunId())).isEqualTo(reconciledNode);
                this.assertRecoveryCannotScheduleOrFabricateEvents(normal);

                final var unrelated = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Unrelated execution after restart."));
                final var pending = this.onlyPending(unrelated.id(), A);
                when(scheduledExecutor.execute(any())).thenAnswer(invocation -> {
                    final NodeExecutionClaim claim = invocation.getArgument(0);
                    assertThat(claim.nodeRunId()).isEqualTo(pending.id());
                    return new AgentExecutionResult(new NodeRunOutput("{\"unrelated\":true}"), null);
                });
                worker.poll();
                executor.submit(() -> {}).get(10, TimeUnit.SECONDS);
                verify(scheduledExecutor).execute(any());
                verifyNoMoreInteractions(scheduledExecutor);
                assertThat(this.workflowRunRepository.findById(unrelated.id()).orElseThrow().status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
                assertThat(this.nodeRunRepository.findById(pending.id()).orElseThrow().status()).isEqualTo(NodeRunStatus.SUCCEEDED);
                verifyNoInteractions(this.agentExecutor);
            } finally {
                releaseOldInspection.countDown();
            }
        }
    }

    private void saveRecoveryWorkflow(final String contextMode) {
        if (NodeContextMode.valueOf(contextMode).iterationScoped()) {
            this.workflowUseCases.updateWorkflow(WORKFLOW_ID, new SaveWorkflowCommand("Recovery iteration",
                    List.of(this.iterationNode(this.node(A, AGENT_A_ID, List.of(this.port(A_IN, "Input")), List.of(this.port(A_OUT, "Result")), 0), NodeContextMode.valueOf(contextMode))),
                    List.of(), A_IN, A_OUT));
        } else if ("REUSE_WITHIN_WORKFLOW_NODE".equals(contextMode)) this.saveReusableTerminalWorkflow();
        else this.saveTerminalWorkflow();
    }

    private void assertRecoveryCannotScheduleOrFabricateEvents(final AgentSessionExecutionClaim normal) {
        assertThat(this.nodeRunRepository.findPendingIds()).doesNotContain(normal.nodeRunId());
        assertThat(this.lifecycle.tryStart(normal.nodeRunId())).isEmpty();
        assertThat(this.agentExecutionEventRepository.findPage(normal.turnId(), 0, 10).orElseThrow()).satisfies(page -> {
            assertThat(page.captureStatus()).isEqualTo(AgentExecutionEventCaptureStatus.DEGRADED);
            assertThat(page.events()).isEmpty();
        });
    }

    private AgentExecutionRecoveryClaim recoveryFence(final AgentExecutionRecoveryClaim claim, final UUID turnId,
                                                      final String ownerId, final long token) {
        return new AgentExecutionRecoveryClaim(claim.sessionId(), turnId, claim.nodeRunId(), claim.workflowRunId(),
                claim.repositoryId(), claim.providerId(), claim.providerVersion(), claim.providerConversationId(),
                claim.providerTurnId(), claim.contextMode(), claim.nodeRunStatus(), claim.failureCode(), claim.failureMessage(),
                ownerId, token, claim.leaseExpiresAt());
    }

    private AgentExecutionRecoveryClaim withRecoveryLeaseExpiry(final AgentExecutionRecoveryClaim claim,
                                                                final Instant leaseExpiresAt) {
        return new AgentExecutionRecoveryClaim(
                claim.sessionId(), claim.turnId(), claim.nodeRunId(), claim.workflowRunId(), claim.repositoryId(),
                claim.providerId(), claim.providerVersion(), claim.providerConversationId(), claim.providerTurnId(),
                claim.contextMode(), claim.nodeRunStatus(), claim.failureCode(), claim.failureMessage(),
                claim.ownerId(), claim.leaseToken(), leaseExpiresAt);
    }

    private AgentSessionExecutionClaim expiredRecoveryExecution() {
        return this.expiredRecoveryExecution("0.153.2");
    }

    private AgentSessionExecutionClaim expiredRecoveryExecution(final String providerVersion) {
        final var run = this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, new CreateWorkflowRunCommand("Recover exact execution."));
        final var normal = this.lifecycle.tryStart(this.onlyPending(run.id(), A).id()).orElseThrow().agentSessionClaim();
        this.agentSessionLeaseService.persistConversation(normal, "thread-" + normal.nodeRunId(), providerVersion);
        this.agentSessionLeaseService.persistTurn(normal, "turn-" + normal.nodeRunId());
        assertThat(this.agentExecutionEventRepository.activate(normal)).isTrue();
        this.jdbcTemplate.update("UPDATE agent_execution_sessions SET lease_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?", normal.sessionId());
        return normal;
    }

    private void recoverTerminal(final NodeExecutionClaim claim) {
        this.agentSessionLeaseService.persistConversation(
                claim.agentSessionClaim(), "thread-" + claim.nodeRunId(), "0.157.0");
        this.agentSessionLeaseService.persistTurn(claim.agentSessionClaim(), "turn-" + claim.nodeRunId());
        assertThat(this.agentExecutionEventRepository.activate(claim.agentSessionClaim())).isTrue();
        this.jdbcTemplate.update(
                "UPDATE agent_execution_sessions SET lease_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",
                claim.agentSessionClaim().sessionId());
        doReturn(ProviderTurnRecoveryResult.terminal(
                ProviderTurnRecoveryTerminalOutcome.SUCCEEDED, "Exact completed turn."))
                .when(this.recoveryInspector).inspect(any());
        assertThat(this.recoveryService.reconcileExpired()).isEqualTo(1);
    }

    private void assertRetryInputCopies(final List<ConnectionResolution> parents,
                                        final List<ConnectionResolution> copies,
                                        final UUID retryNodeRunId) {
        assertThat(copies).hasSameSizeAs(parents);
        for (int index = 0; index < parents.size(); index++) {
            final ConnectionResolution parent = parents.get(index);
            final ConnectionResolution copy = copies.get(index);
            assertThat(copy.id()).isNotEqualTo(parent.id());
            assertThat(copy.consumedByNodeRunId()).isEqualTo(retryNodeRunId);
            assertThat(copy).usingRecursiveComparison()
                    .ignoringFields("id", "consumedByNodeRunId", "createdAt")
                    .isEqualTo(parent);
        }
    }

    @Test
    void expiredOwnerResultIsRejectedAfterRecoveryTakesNextFenceToken() {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(
                WORKFLOW_ID, new CreateWorkflowRunCommand("Run once."));
        final NodeRun pending = this.onlyPending(run.id(), A);
        final NodeExecutionClaim staleClaim = this.lifecycle.tryStart(pending.id()).orElseThrow();
        this.agentSessionLeaseService.persistConversation(staleClaim.agentSessionClaim(), "thread-stale", "0.153.2");
        this.agentSessionLeaseService.persistTurn(staleClaim.agentSessionClaim(), "provider-turn-stale");
        assertThat(this.agentExecutionEventRepository.activate(staleClaim.agentSessionClaim())).isTrue();
        this.jdbcTemplate.update(
                "UPDATE agent_execution_sessions SET lease_expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?",
                staleClaim.agentSessionClaim().sessionId()
        );

        assertThat(this.recoveryService.reconcileExpired()).isEqualTo(1);
        assertThat(this.agentExecutionEventRepository.append(staleClaim.agentSessionClaim(), event(
                AgentExecutionEventType.WARNING, null, null))).isEqualTo(AgentExecutionEventAppendResult.STALE);
        assertThatThrownBy(() -> this.lifecycle.succeed(
                pending.id(), this.result(pending, "{\"late\":true}"), staleClaim.agentSessionClaim()
        )).isInstanceOf(com.sitionix.forgeagent.domain.exception.ConflictException.class)
                .extracting("code").isEqualTo("STALE_AGENT_SESSION_LEASE");
        assertThat(this.nodeRunRepository.findById(pending.id()).orElseThrow()).satisfies(nodeRun -> {
            assertThat(nodeRun.status()).isEqualTo(NodeRunStatus.FAILED);
            assertThat(nodeRun.failure().code()).isEqualTo("AGENT_EXECUTION_RECOVERY_UNKNOWN");
        });
        assertThat(this.agentExecutionSessionRepository.findByNodeRunId(pending.id()).orElseThrow())
                .satisfies(allocation -> {
                    assertThat(allocation.turn().status()).isEqualTo(AgentExecutionTurnStatus.FAILED);
                    assertThat(allocation.session().status()).isEqualTo(AgentExecutionSessionStatus.FAILED);
                    assertThat(allocation.session().leaseOwnerId()).isNull();
                    assertThat(allocation.session().leaseToken())
                            .isEqualTo(staleClaim.agentSessionClaim().leaseToken() + 1);
                });
        assertThat(this.agentExecutionEventRepository.findPage(
                staleClaim.agentSessionClaim().turnId(), 0, 10).orElseThrow()).satisfies(page -> {
                    assertThat(page.captureStatus()).isEqualTo(AgentExecutionEventCaptureStatus.DEGRADED);
                    assertThat(page.events()).isEmpty();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_STARTED", "COMPLETE", "DEGRADED", "NULL"})
    void expiredRecoveryPreservesNonActiveCaptureStates(final String captureStatus) {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(
                WORKFLOW_ID, new CreateWorkflowRunCommand("Recover preserved capture."));
        final NodeRun pending = this.onlyPending(run.id(), A);
        final NodeExecutionClaim claim = this.lifecycle.tryStart(pending.id()).orElseThrow();
        this.agentSessionLeaseService.persistConversation(claim.agentSessionClaim(), "thread-expired-preserved", "0.153.2");
        this.agentSessionLeaseService.persistTurn(claim.agentSessionClaim(), "provider-turn-expired-preserved");
        this.jdbcTemplate.update("UPDATE agent_execution_turns SET event_capture_status=? WHERE id=?",
                "NULL".equals(captureStatus) ? null : captureStatus, claim.agentSessionClaim().turnId());
        this.jdbcTemplate.update(
                "UPDATE agent_execution_sessions SET lease_expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?",
                claim.agentSessionClaim().sessionId());

        assertThat(this.recoveryService.reconcileExpired()).isEqualTo(1);

        assertThat(this.captureStatus(claim.agentSessionClaim().turnId()))
                .isEqualTo("NULL".equals(captureStatus) ? null : captureStatus);
        assertThat(this.agentExecutionSessionRepository.findByNodeRunId(pending.id()).orElseThrow().turn().status())
                .isEqualTo(AgentExecutionTurnStatus.FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "NOT_STARTED", "COMPLETE", "DEGRADED", "NULL"})
    void terminalRecoveryDegradesActiveAndPreservesOtherCaptureStates(final String captureStatus) {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(
                WORKFLOW_ID, new CreateWorkflowRunCommand("Reconcile terminal turn."));
        final NodeRun pending = this.onlyPending(run.id(), A);
        final NodeExecutionClaim claim = this.lifecycle.tryStart(pending.id()).orElseThrow();
        this.agentSessionLeaseService.persistConversation(claim.agentSessionClaim(), "thread-terminal", "0.153.2");
        this.agentSessionLeaseService.persistTurn(claim.agentSessionClaim(), "provider-turn-terminal");
        this.jdbcTemplate.update("UPDATE agent_execution_turns SET event_capture_status=? WHERE id=?",
                "NULL".equals(captureStatus) ? null : captureStatus, claim.agentSessionClaim().turnId());
        this.jdbcTemplate.update("""
                UPDATE node_runs
                   SET status='FAILED',failure_code='AGENT_CONTEXT_PERSISTENCE_FAILED',
                       failure_message='Persisted terminal failure.',finished_at=CURRENT_TIMESTAMP
                 WHERE id=?
                """, pending.id());
        this.jdbcTemplate.update(
                "UPDATE agent_execution_sessions SET lease_expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?",
                claim.agentSessionClaim().sessionId());

        assertThat(this.recoveryService.reconcileExpired()).isEqualTo(1);

        assertThat(this.captureStatus(claim.agentSessionClaim().turnId()))
                .isEqualTo(switch (captureStatus) {
                    case "ACTIVE" -> "DEGRADED";
                    case "NULL" -> null;
                    default -> captureStatus;
                });
        assertThat(this.agentExecutionSessionRepository.findByNodeRunId(pending.id()).orElseThrow())
                .satisfies(allocation -> {
                    assertThat(allocation.turn().status()).isEqualTo(AgentExecutionTurnStatus.FAILED);
                    assertThat(allocation.session().status()).isEqualTo(AgentExecutionSessionStatus.FAILED);
                });
    }

    @Test
    void cancellationDegradesActiveCaptureAndFencesPreviousOwnerWithoutFabricatingEvents() {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(
                WORKFLOW_ID, new CreateWorkflowRunCommand("Cancel active capture."));
        final NodeRun pending = this.onlyPending(run.id(), A);
        final NodeExecutionClaim claim = this.lifecycle.tryStart(pending.id()).orElseThrow();
        this.agentSessionLeaseService.persistConversation(claim.agentSessionClaim(), "thread-cancel", "0.153.2");
        this.agentSessionLeaseService.persistTurn(claim.agentSessionClaim(), "provider-turn-cancel");
        assertThat(this.agentExecutionEventRepository.activate(claim.agentSessionClaim())).isTrue();

        assertThat(this.agentExecutionSessionRepository.cancel(pending.id())).isTrue();

        assertThat(this.nodeRunRepository.findById(pending.id()).orElseThrow().status())
                .isEqualTo(NodeRunStatus.CANCELLED);
        assertThat(this.agentExecutionSessionRepository.findByNodeRunId(pending.id()).orElseThrow())
                .satisfies(allocation -> {
                    assertThat(allocation.turn().status()).isEqualTo(AgentExecutionTurnStatus.CANCELLED);
                    assertThat(allocation.session().status()).isEqualTo(AgentExecutionSessionStatus.CLOSED);
                    assertThat(allocation.session().leaseOwnerId()).isNull();
                    assertThat(allocation.session().leaseToken())
                            .isEqualTo(claim.agentSessionClaim().leaseToken() + 1);
                });
        assertThat(this.agentExecutionEventRepository.append(claim.agentSessionClaim(), event(
                AgentExecutionEventType.WARNING, null, null))).isEqualTo(AgentExecutionEventAppendResult.STALE);
        assertThat(this.agentExecutionEventRepository.findPage(
                claim.agentSessionClaim().turnId(), 0, 10).orElseThrow()).satisfies(page -> {
                    assertThat(page.captureStatus()).isEqualTo(AgentExecutionEventCaptureStatus.DEGRADED);
                    assertThat(page.events()).isEmpty();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_STARTED", "COMPLETE", "DEGRADED", "NULL"})
    void cancellationPreservesAlreadyTerminalOrHistoricalCaptureState(final String captureStatus) {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(
                WORKFLOW_ID, new CreateWorkflowRunCommand("Cancel preserved capture."));
        final NodeRun pending = this.onlyPending(run.id(), A);
        final NodeExecutionClaim claim = this.lifecycle.tryStart(pending.id()).orElseThrow();
        this.agentSessionLeaseService.persistConversation(claim.agentSessionClaim(), "thread-cancel-preserved", "0.153.2");
        this.agentSessionLeaseService.persistTurn(claim.agentSessionClaim(), "provider-turn-cancel-preserved");
        this.jdbcTemplate.update("UPDATE agent_execution_turns SET event_capture_status=? WHERE id=?",
                "NULL".equals(captureStatus) ? null : captureStatus, claim.agentSessionClaim().turnId());

        assertThat(this.agentExecutionSessionRepository.cancel(pending.id())).isTrue();

        assertThat(this.captureStatus(claim.agentSessionClaim().turnId()))
                .isEqualTo("NULL".equals(captureStatus) ? null : captureStatus);
    }

    @Test
    void executionEventLedgerSequencesDeduplicatesFencesAndPreservesLegacyCaptureState() throws Exception {
        this.seed();
        this.saveReusableTerminalWorkflow();

        final WorkflowRun firstRun = this.workflowRunUseCases.createWorkflowRun(
                WORKFLOW_ID, new CreateWorkflowRunCommand("Capture first turn."));
        final NodeExecutionClaim first = this.lifecycle.tryStart(this.onlyPending(firstRun.id(), A).id()).orElseThrow();
        this.agentSessionLeaseService.persistConversation(first.agentSessionClaim(), "thread-ledger-1", "0.153.2");
        assertThat(this.agentExecutionEventRepository.activate(first.agentSessionClaim())).isFalse();
        assertThat(this.agentExecutionEventRepository.append(first.agentSessionClaim(), event(
                AgentExecutionEventType.WARNING, null, null))).isEqualTo(AgentExecutionEventAppendResult.STALE);
        assertThat(this.agentExecutionEventRepository.findPage(first.agentSessionClaim().turnId(), 0, 10)
                .orElseThrow()).satisfies(page -> {
                    assertThat(page.captureStatus()).isEqualTo(AgentExecutionEventCaptureStatus.NOT_STARTED);
                    assertThat(page.events()).isEmpty();
                });
        this.agentSessionLeaseService.persistTurn(first.agentSessionClaim(), "turn-ledger-1");
        assertThat(this.agentExecutionEventRepository.activate(first.agentSessionClaim())).isTrue();

        assertThat(this.agentExecutionEventRepository.append(first.agentSessionClaim(), event(
                AgentExecutionEventType.TURN, AgentExecutionEventStatus.STARTED, "turn:turn-ledger-1:started")))
                .isEqualTo(AgentExecutionEventAppendResult.APPENDED);
        assertThat(this.agentExecutionEventRepository.append(first.agentSessionClaim(), event(
                AgentExecutionEventType.COMMAND, AgentExecutionEventStatus.FAILED, "item:cmd-1:completed")))
                .isEqualTo(AgentExecutionEventAppendResult.APPENDED);
        assertThat(this.agentExecutionEventRepository.append(first.agentSessionClaim(), event(
                AgentExecutionEventType.COMMAND, AgentExecutionEventStatus.FAILED, "item:cmd-1:completed")))
                .isEqualTo(AgentExecutionEventAppendResult.DUPLICATE);
        assertThat(this.agentExecutionEventRepository.append(first.agentSessionClaim(), event(
                AgentExecutionEventType.TOKEN_USAGE, null, null)))
                .isEqualTo(AgentExecutionEventAppendResult.APPENDED);
        assertThat(this.agentExecutionEventRepository.append(first.agentSessionClaim(), event(
                AgentExecutionEventType.TOKEN_USAGE, null, null)))
                .isEqualTo(AgentExecutionEventAppendResult.APPENDED);

        final var firstPage = this.agentExecutionEventRepository.findPage(first.agentSessionClaim().turnId(), 0, 10)
                .orElseThrow();
        assertThat(firstPage.captureStatus()).isEqualTo(AgentExecutionEventCaptureStatus.ACTIVE);
        assertThat(firstPage.events()).extracting(com.sitionix.forgeagent.domain.model.AgentExecutionEvent::sequence)
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(this.agentExecutionEventRepository.findPage(first.agentSessionClaim().turnId(), 2, 1).orElseThrow())
                .satisfies(page -> {
                    assertThat(page.events()).extracting(com.sitionix.forgeagent.domain.model.AgentExecutionEvent::sequence)
                            .containsExactly(3L);
                    assertThat(page.hasMore()).isTrue();
                    assertThat(page.nextAfterSequence()).isEqualTo(3L);
                });

        try (var appends = Executors.newFixedThreadPool(2)) {
            final var left = appends.submit(() -> this.agentExecutionEventRepository.append(
                    first.agentSessionClaim(), event(AgentExecutionEventType.PLAN, null, null)));
            final var right = appends.submit(() -> this.agentExecutionEventRepository.append(
                    first.agentSessionClaim(), event(AgentExecutionEventType.PLAN, null, null)));
            assertThat(List.of(left.get(), right.get())).containsOnly(AgentExecutionEventAppendResult.APPENDED);
        }
        assertThat(this.agentExecutionEventRepository.findPage(first.agentSessionClaim().turnId(), 0, 10)
                .orElseThrow().events())
                .extracting(com.sitionix.forgeagent.domain.model.AgentExecutionEvent::sequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);

        final WorkflowRun secondRun = this.workflowRunUseCases.createWorkflowRun(
                WORKFLOW_ID, new CreateWorkflowRunCommand("Capture independent turn."));
        final NodeExecutionClaim second = this.lifecycle.tryStart(this.onlyPending(secondRun.id(), A).id()).orElseThrow();
        this.agentSessionLeaseService.persistConversation(second.agentSessionClaim(), "thread-ledger-2", "0.153.2");
        this.agentSessionLeaseService.persistTurn(second.agentSessionClaim(), "turn-ledger-2");
        assertThat(this.agentExecutionEventRepository.activate(second.agentSessionClaim())).isTrue();
        assertThat(this.agentExecutionEventRepository.append(second.agentSessionClaim(), event(
                AgentExecutionEventType.TURN, AgentExecutionEventStatus.STARTED, "turn:turn-ledger-2:started")))
                .isEqualTo(AgentExecutionEventAppendResult.APPENDED);
        assertThat(this.agentExecutionEventRepository.findPage(second.agentSessionClaim().turnId(), 0, 10)
                .orElseThrow().events()).extracting(com.sitionix.forgeagent.domain.model.AgentExecutionEvent::sequence)
                .containsExactly(1L);

        final long nextToken = first.agentSessionClaim().leaseToken() + 1;
        this.jdbcTemplate.update("""
                UPDATE agent_execution_sessions
                   SET lease_owner_id='worker-b',lease_token=?,lease_expires_at=CURRENT_TIMESTAMP + INTERVAL '1 minute'
                 WHERE id=?
                """, nextToken, first.agentSessionClaim().sessionId());
        assertThat(this.agentExecutionEventRepository.append(first.agentSessionClaim(), event(
                AgentExecutionEventType.WARNING, null, null))).isEqualTo(AgentExecutionEventAppendResult.STALE);
        final AgentSessionExecutionClaim current = new AgentSessionExecutionClaim(
                first.agentSessionClaim().sessionId(), first.agentSessionClaim().turnId(), first.nodeRunId(),
                "worker-b", nextToken, Instant.now().plusSeconds(60), "thread-ledger-1", "codex",
                first.agentSessionClaim().contextMode(), "0.153.2");
        assertThat(this.agentExecutionEventRepository.append(current, event(
                AgentExecutionEventType.WARNING, null, null))).isEqualTo(AgentExecutionEventAppendResult.APPENDED);

        assertThatThrownBy(() -> this.jdbcTemplate.update(
                "UPDATE agent_execution_events SET phase='changed' WHERE agent_turn_id=?",
                first.agentSessionClaim().turnId())).hasMessageContaining("append-only");
        assertThatThrownBy(() -> this.jdbcTemplate.update("""
                INSERT INTO agent_execution_events(
                  id,agent_session_id,agent_turn_id,node_run_id,sequence,type,payload,occurred_at,created_at)
                VALUES (?,?,?,?,99,'TURN','{}'::jsonb,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), first.agentSessionClaim().sessionId(), first.agentSessionClaim().turnId(),
                UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        final WorkflowRun legacyRun = this.workflowRunUseCases.createWorkflowRun(
                WORKFLOW_ID, new CreateWorkflowRunCommand("Represent legacy turn."));
        final NodeExecutionClaim legacy = this.lifecycle.tryStart(this.onlyPending(legacyRun.id(), A).id()).orElseThrow();
        this.jdbcTemplate.update("UPDATE agent_execution_turns SET event_capture_status=NULL WHERE id=?",
                legacy.agentSessionClaim().turnId());
        assertThat(this.agentExecutionEventRepository.findPage(legacy.agentSessionClaim().turnId(), 0, 10)
                .orElseThrow().captureStatus()).isEqualTo(AgentExecutionEventCaptureStatus.UNAVAILABLE);
    }

    @Test
    void failedTurnTerminalEventLeavesCaptureComplete() {
        this.seed();
        this.saveReusableTerminalWorkflow();
        final WorkflowRun run = this.workflowRunUseCases.createWorkflowRun(
                WORKFLOW_ID, new CreateWorkflowRunCommand("Capture failed turn."));
        final NodeExecutionClaim claim = this.lifecycle.tryStart(this.onlyPending(run.id(), A).id()).orElseThrow();
        this.agentSessionLeaseService.persistConversation(claim.agentSessionClaim(), "thread-failed", "0.153.2");
        this.agentSessionLeaseService.persistTurn(claim.agentSessionClaim(), "turn-failed");
        assertThat(this.agentExecutionEventRepository.activate(claim.agentSessionClaim())).isTrue();
        assertThat(this.agentExecutionEventRepository.append(claim.agentSessionClaim(), event(
                AgentExecutionEventType.TURN, AgentExecutionEventStatus.STARTED, "turn:turn-failed:started")))
                .isEqualTo(AgentExecutionEventAppendResult.APPENDED);
        assertThat(this.agentExecutionEventRepository.append(claim.agentSessionClaim(), event(
                AgentExecutionEventType.TURN, AgentExecutionEventStatus.FAILED, "turn:turn-failed:failed")))
                .isEqualTo(AgentExecutionEventAppendResult.APPENDED);
        assertThat(this.agentExecutionEventRepository.markComplete(claim.agentSessionClaim())).isTrue();

        assertThat(this.agentExecutionEventRepository.findPage(claim.agentSessionClaim().turnId(), 0, 10).orElseThrow())
                .satisfies(page -> {
                    assertThat(page.captureStatus()).isEqualTo(AgentExecutionEventCaptureStatus.COMPLETE);
                    assertThat(page.events()).extracting(
                                    com.sitionix.forgeagent.domain.model.AgentExecutionEvent::status)
                            .containsExactly(AgentExecutionEventStatus.STARTED, AgentExecutionEventStatus.FAILED);
                });
    }

    private static AgentExecutionEventCandidate event(final AgentExecutionEventType type,
                                                       final AgentExecutionEventStatus status,
                                                       final String providerEventKey) {
        return new AgentExecutionEventCandidate(type, status, null, providerEventKey, "{}", Instant.now());
    }

    private String captureStatus(final UUID turnId) {
        return this.jdbcTemplate.queryForObject(
                "SELECT event_capture_status FROM agent_execution_turns WHERE id=?", String.class, turnId);
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
            final var first = executor.submit(() -> this.succeedStarted(strategy, this.result(strategy, "{\"strategy\":\"return\"}")));
            final var second = executor.submit(() -> this.succeedStarted(code, this.result(code, "{\"code\":\"return\"}")));
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
        this.completionPersistence.markBusinessSucceeded(runningA.id(), this.result(runningA, "{\"step\":\"A\"}"),
                this.sessionClaims.get(runningA.id()).agentSessionClaim());
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
        this.completionPersistence.markBusinessSucceeded(strategy.id(), this.result(strategy, "{\"strategy\":\"pass\"}"),
                this.sessionClaims.get(strategy.id()).agentSessionClaim());

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
                new AgentExecutionResult(new NodeRunOutput("{\"route\":\"return\"}"), A_RETURN),
                this.sessionClaims.get(runningA.id()).agentSessionClaim());

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
        this.completionPersistence.markBusinessSucceeded(runningA.id(), this.result(runningA, "{\"done\":true}"),
                this.sessionClaims.get(runningA.id()).agentSessionClaim());
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
                    execution_model_provider_id, execution_model_id, execution_model_effort_id, context_mode, created_at, node_type
                )
                VALUES (
                    :nodeRunId, :runId, :sourceNodeId, :agentId, 'Legacy Agent', 'Legacy instructions.',
                    CAST(:schema AS jsonb), 'DEPENDENCIES_ONLY', 0, 0, 'PENDING',
                    'codex', 'discovered-model', 'medium', 'FRESH_EACH_NODE_RUN', CURRENT_TIMESTAMP, 'AGENT'
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
        while (current != null && !Files.exists(current.resolve(".git"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Forge root could not be resolved for integration test.");
        }
        return current.resolve("forge-projects").resolve(PROJECT_ALPHA_ID.toString());
    }

    private AgentExecutionResult executeLiveWithHeartbeat(final NodeExecutionClaim claim) {
        final var scheduler = Executors.newSingleThreadScheduledExecutor();
        final var heartbeat = scheduler.scheduleAtFixedRate(
                () -> this.agentSessionLeaseService.renew(claim.agentSessionClaim()),
                AgentSessionLeaseService.HEARTBEAT_SECONDS,
                AgentSessionLeaseService.HEARTBEAT_SECONDS,
                TimeUnit.SECONDS);
        try {
            return this.agentExecutor.execute(claim);
        } finally {
            heartbeat.cancel(false);
            scheduler.shutdownNow();
        }
    }

    private void awaitLiveCommand(final NodeExecutionClaim claim,
                                  final CompletableFuture<AgentExecutionResult> execution) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        boolean exactTurnKnown = false;
        boolean commandRecorded = false;
        boolean cancellationAvailable = false;
        while (System.nanoTime() < deadline) {
            if (execution.isDone()) {
                try {
                    execution.join();
                    throw new AssertionError("Live Codex execution completed before the cancellation boundary.");
                } catch (final java.util.concurrent.CompletionException failure) {
                    throw new AssertionError("Live Codex execution failed before the cancellation boundary.",
                            failure.getCause());
                }
            }
            exactTurnKnown = this.agentExecutionSessionRepository
                    .findByWorkflowRunId(claim.workflowRunId()).stream()
                    .anyMatch(allocation -> allocation.turn().nodeRunId().equals(claim.nodeRunId())
                            && allocation.turn().providerTurnId() != null
                            && !allocation.turn().providerTurnId().isBlank());
            commandRecorded = this.agentExecutionEventRepository
                    .findPage(claim.agentSessionClaim().turnId(), 0, 200)
                    .map(page -> page.events().stream()
                            .anyMatch(event -> event.type() == AgentExecutionEventType.COMMAND))
                    .orElse(false);
            cancellationAvailable = this.agentExecutor.secureCancellation(claim.nodeRunId()).isPresent();
            if (exactTurnKnown && commandRecorded && cancellationAvailable) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Live Codex command did not become cancellable before the acceptance deadline: "
                + "exactTurnKnown=" + exactTurnKnown + ", commandRecorded=" + commandRecorded
                + ", cancellationAvailable=" + cancellationAvailable);
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

    private void saveReusableTerminalWorkflow() {
        this.workflowUseCases.updateWorkflow(WORKFLOW_ID, new SaveWorkflowCommand(
                "Full Testing",
                List.of(new Node(A, AGENT_A_ID, NodeInputMode.DEPENDENCIES_ONLY,
                        List.of(this.port(A_IN, "Input")), List.of(this.port(A_OUT, "Done")),
                        new NodePosition(0.0, 0.0), NodeScopeMode.GLOBAL,
                        NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE)),
                List.of(), A_IN, A_OUT
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

    private void saveReusableReviewerWorkflow() {
        this.workflowUseCases.updateWorkflow(WORKFLOW_ID, new SaveWorkflowCommand(
                "Full Testing",
                List.of(
                        new Node(IMPLEMENTER, AGENT_A_ID, NodeInputMode.DEPENDENCIES_ONLY,
                                List.of(this.port(IMPLEMENTER_INITIAL_IN, "Initial", 0), this.port(IMPLEMENTER_REVIEW_IN, "Review", 1)),
                                List.of(this.port(IMPLEMENTER_OUT, "Done")), new NodePosition(0.0, 0.0),
                                NodeScopeMode.GLOBAL, NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE),
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

    private void saveIterationReviewerWorkflow() {
        this.workflowUseCases.updateWorkflow(WORKFLOW_ID, new SaveWorkflowCommand(
                "Full Testing",
                List.of(
                        new Node(IMPLEMENTER, AGENT_A_ID, NodeInputMode.DEPENDENCIES_ONLY,
                                List.of(this.port(IMPLEMENTER_INITIAL_IN, "Initial", 0), this.port(IMPLEMENTER_REVIEW_IN, "Review", 1)),
                                List.of(this.port(IMPLEMENTER_OUT, "Done")), new NodePosition(0.0, 0.0),
                                NodeScopeMode.GLOBAL, NodeContextMode.REUSE_WITHIN_WORKFLOW_ITERATION, "implementation-review"),
                        this.iterationNode(this.node(STRATEGY, AGENT_B_ID, List.of(this.port(STRATEGY_IN, "Input")),
                                List.of(this.port(STRATEGY_PASS, "Pass", 0), this.port(STRATEGY_RETURN, "Return", 1)), 1)),
                        this.iterationNode(this.node(CODE, AGENT_C_ID, List.of(this.port(CODE_IN, "Input")),
                                List.of(this.port(CODE_PASS, "Pass", 0), this.port(CODE_RETURN, "Return", 1)), 2))
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
        this.sessionClaims.put(nodeRun.id(), claim);
        return this.nodeRunRepository.findById(claim.nodeRunId()).orElseThrow();
    }

    private void complete(final NodeRun nodeRun, final String output) {
        final NodeExecutionClaim claim = this.lifecycle.tryStart(nodeRun.id()).orElseThrow(() -> new AssertionError(
                "Could not claim " + this.nodeRunRepository.findById(nodeRun.id())
                        + "; allocation=" + this.agentExecutionSessionRepository.findByNodeRunId(nodeRun.id())
                        + "; workflow=" + this.workflowRunRepository.findById(nodeRun.workflowRunId())
        ));
        final NodeRunOutput businessOutput = new NodeRunOutput(output);
        final UUID selected = claim.availableOutputs().size() > 1
                ? this.outputSelector.selectOutput(businessOutput, claim.availableOutputs(), claim.executionModel())
                : null;
        this.lifecycle.succeed(claim.nodeRunId(), new AgentExecutionResult(businessOutput, selected), claim.agentSessionClaim());
    }

    private void succeedStarted(final NodeRun nodeRun, final AgentExecutionResult result) {
        final NodeExecutionClaim claim = this.sessionClaims.remove(nodeRun.id());
        this.lifecycle.succeed(nodeRun.id(), result, claim == null ? null : claim.agentSessionClaim());
    }

    private AgentExecutionResult result(final NodeRun nodeRun, final String output) {
        final NodeRunOutput businessOutput = new NodeRunOutput(output);
        final List<com.sitionix.forgeagent.domain.model.RunPort> outputs = this.graphRepository.findOutputPortsByNode(
                nodeRun.workflowRunId(), nodeRun.sourceNodeId());
        final UUID selected = outputs.size() > 1
                ? this.outputSelector.selectOutput(businessOutput, outputs, nodeRun.executionModel())
                : null;
        return new AgentExecutionResult(businessOutput, selected);
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
