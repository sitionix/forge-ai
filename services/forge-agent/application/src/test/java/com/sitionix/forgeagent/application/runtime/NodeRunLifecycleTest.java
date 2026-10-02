package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.AgentSessionExecutionClaim;
import com.sitionix.forgeagent.domain.model.AgentExecutionAllocation;
import com.sitionix.forgeagent.domain.model.AgentExecutionSession;
import com.sitionix.forgeagent.domain.model.AgentExecutionSessionStatus;
import com.sitionix.forgeagent.domain.model.AgentExecutionTurn;
import com.sitionix.forgeagent.domain.model.AgentExecutionTurnStatus;
import com.sitionix.forgeagent.domain.model.McpAllowedTool;
import com.sitionix.forgeagent.domain.model.McpAuthType;
import com.sitionix.forgeagent.domain.model.McpConnection;
import com.sitionix.forgeagent.domain.model.McpProjectAccess;
import com.sitionix.forgeagent.domain.model.McpRuntimeGrant;
import com.sitionix.forgeagent.domain.model.McpRuntimeGrantHandle;
import com.sitionix.forgeagent.domain.model.McpToolCallResult;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.model.NodeInputEnvelope;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodeContextMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.ConnectionResolutionRepository;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import com.sitionix.forgeagent.domain.port.ForgeInstanceIdentityRepository;
import com.sitionix.forgeagent.domain.port.McpConnectionRepository;
import com.sitionix.forgeagent.domain.port.McpCredentialCipher;
import com.sitionix.forgeagent.domain.port.McpOAuthClient;
import com.sitionix.forgeagent.domain.port.McpOAuthCredentialCipher;
import com.sitionix.forgeagent.domain.port.McpRemoteToolClient;
import com.sitionix.forgeagent.domain.port.McpRuntimeGrantRepository;
import com.sitionix.forgeagent.domain.port.McpRuntimeToolView;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import com.sitionix.forgeagent.application.mcp.McpCredentialService;
import com.sitionix.forgeagent.application.mcp.McpExecutionSelectionService;
import com.sitionix.forgeagent.application.mcp.McpGatewayAccessException;
import com.sitionix.forgeagent.application.mcp.McpGatewayService;

@ExtendWith(MockitoExtension.class)
class NodeRunLifecycleTest {

    private static final UUID WORKFLOW_RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID WORKFLOW_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID AGENT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID NODE_RUN_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID FRAME_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-11T10:15:30Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AgentOutputSchema SCHEMA = AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}");
    private static final NodeRunExecutionModel MODEL = new NodeRunExecutionModel("codex", "gpt-5", null);

    @Mock
    private NodeRunRepository nodeRunRepository;
    @Mock
    private WorkflowRunRepository workflowRunRepository;
    @Mock
    private ConnectionResolutionRepository resolutionRepository;
    @Mock
    private NodeInputContentPolicyRegistry inputContentPolicyRegistry;
    @Mock
    private WorkflowExecutionCoordinator coordinator;
    @Mock
    private WorkflowCompletionPolicy completionPolicy;
    @Mock
    private NodeRunCompletionProcessor completionProcessor;
    @Mock
    private ExecutionWorkspaceResolver executionWorkspaceResolver;
    @Mock
    private WorkflowRunGraphRepository graphRepository;
    @Mock
    private AgentSessionLeaseService sessionLeaseService;

    private final Map<UUID, NodeRun> nodeRuns = new LinkedHashMap<>();
    private WorkflowRun workflowRun;
    private NodeRunLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        this.lifecycle = new NodeRunLifecycle(
                this.nodeRunRepository,
                this.workflowRunRepository,
                this.resolutionRepository,
                this.inputContentPolicyRegistry,
                this.coordinator,
                this.completionPolicy,
                CLOCK,
                new NodeRunCompletionPersistence(
                        this.nodeRunRepository,
                        this.workflowRunRepository,
                        this.completionPolicy,
                        this.coordinator,
                        CLOCK,
                        this.sessionLeaseService
                ),
                this.completionProcessor,
                this.executionWorkspaceResolver,
                this.graphRepository,
                this.sessionLeaseService
        );
        this.workflowRun = this.workflowRun(WorkflowRunStatus.QUEUED, null, null);
        this.stubRepositories();
        lenient().when(this.executionWorkspaceResolver.resolve(
                        any(), any()))
                .thenReturn(new ExecutionWorkspace(java.nio.file.Path.of("/forge/project"), List.of()));
    }

    @Test
    void pendingNodeClaimsUsingSnapshottedExecutionModelAndInputContentPolicy() {
        this.nodeRuns.put(NODE_RUN_ID, this.nodeRun(NodeRunStatus.PENDING, MODEL));
        when(this.resolutionRepository.findConsumedByNodeRunId(NODE_RUN_ID)).thenReturn(List.of());
        when(this.inputContentPolicyRegistry.assemble(any())).thenReturn(new NodeExecutionInputContent(
                new NodeInputEnvelope("Review auth changes.", null, List.of())
        ));

        final Optional<NodeExecutionClaim> claim = this.lifecycle.tryStart(NODE_RUN_ID);

        assertThat(claim).isPresent();
        assertThat(this.nodeRuns.get(NODE_RUN_ID).status()).isEqualTo(NodeRunStatus.RUNNING);
        assertThat(this.workflowRun.status()).isEqualTo(WorkflowRunStatus.RUNNING);
        assertThat(claim.orElseThrow()).satisfies(execution -> {
            assertThat(execution.nodeRunId()).isEqualTo(NODE_RUN_ID);
            assertThat(execution.executionModel()).isEqualTo(MODEL);
            assertThat(execution.workflowInput()).isEqualTo("Review auth changes.");
            assertThat(execution.inputEnvelope().originalTask()).isEqualTo("Review auth changes.");
            assertThat(execution.inputEnvelope().contributions()).isEmpty();
        });
    }

    @Test
    void trackedNodeStartPreparesMcpAndDispatchAuthorizationAllowsCallBeforeTurnPersistence() {
        this.nodeRuns.put(NODE_RUN_ID, this.trackedNodeRun(NodeRunStatus.PENDING));
        when(this.resolutionRepository.findConsumedByNodeRunId(NODE_RUN_ID)).thenReturn(List.of());
        when(this.inputContentPolicyRegistry.assemble(any())).thenReturn(new NodeExecutionInputContent(
                new NodeInputEnvelope("Read only MCP acceptance.", null, List.of())));

        UUID sessionId = UUID.randomUUID(), turnId = UUID.randomUUID(), installation = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        var sessions = mock(AgentExecutionSessionRepository.class);
        var projects = mock(ProjectRepository.class);
        var connections = mock(McpConnectionRepository.class);
        var grants = mock(McpRuntimeGrantRepository.class);
        var views = mock(McpRuntimeToolView.class);
        var remote = mock(McpRemoteToolClient.class);
        ForgeInstanceIdentityRepository identity = () -> installation;
        var approval = new McpAllowedTool("read", "sha256:" + "a".repeat(64));
        var connection = new McpConnection(connectionId, installation, "fixture", URI.create("https://example.org/mcp"),
                McpAuthType.NONE, true, McpProjectAccess.selected(java.util.Set.of(PROJECT_ID)),
                java.util.Set.of(approval), false, NOW, NOW, null, null, null, null);
        var sessionState = new AgentExecutionSession[1];
        var turnState = new AgentExecutionTurn[1];
        var claimed = new AgentSessionExecutionClaim[1];
        when(sessions.acquire(org.mockito.ArgumentMatchers.eq(NODE_RUN_ID), any())).thenAnswer(invocation -> {
            String owner = invocation.getArgument(1);
            claimed[0] = new AgentSessionExecutionClaim(sessionId, turnId, NODE_RUN_ID, owner, 7L,
                    NOW.plusSeconds(60), null, "codex");
            sessionState[0] = new AgentExecutionSession(sessionId, WORKFLOW_RUN_ID, UUID.randomUUID(), AGENT_ID,
                    null, "codex", null, null, NodeContextMode.FRESH_EACH_NODE_RUN,
                    AgentExecutionSessionStatus.CREATING, null, NODE_RUN_ID, owner, 7L, NOW.plusSeconds(60),
                    null, null, NOW, NOW, null, null);
            turnState[0] = new AgentExecutionTurn(turnId, sessionId, NODE_RUN_ID, null, 1,
                    AgentExecutionTurnStatus.STARTING, null, null, null, null, null, NOW, null, NOW, NOW);
            this.nodeRuns.put(NODE_RUN_ID, this.trackedNodeRun(NodeRunStatus.RUNNING));
            return Optional.of(claimed[0]);
        });
        when(sessions.findSession(sessionId)).thenAnswer(ignored -> Optional.of(sessionState[0]));
        when(sessions.findByNodeRunId(NODE_RUN_ID)).thenAnswer(ignored -> Optional.of(
                new AgentExecutionAllocation(sessionState[0], turnState[0])));
        when(sessions.lockCurrentLease(org.mockito.ArgumentMatchers.eq(sessionId), any(),
                org.mockito.ArgumentMatchers.eq(7L))).thenReturn(true);
        when(sessions.persistProviderTurn(org.mockito.ArgumentMatchers.eq(sessionId),
                org.mockito.ArgumentMatchers.eq(turnId), any(), org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("provider-turn"))).thenAnswer(ignored -> {
            var before = sessionState[0];
            sessionState[0] = new AgentExecutionSession(sessionId, WORKFLOW_RUN_ID, before.sourceNodeId(), AGENT_ID,
                    null, "codex", null, null, NodeContextMode.FRESH_EACH_NODE_RUN,
                    AgentExecutionSessionStatus.ACTIVE, null, NODE_RUN_ID, before.leaseOwnerId(), 7L,
                    NOW.plusSeconds(60), null, null, NOW, NOW, null, null);
            turnState[0] = new AgentExecutionTurn(turnId, sessionId, NODE_RUN_ID, "provider-turn", 1,
                    AgentExecutionTurnStatus.ACTIVE, null, null, null, null, null, NOW, null, NOW, NOW);
            return true;
        });
        when(sessions.finish(org.mockito.ArgumentMatchers.eq(sessionId), org.mockito.ArgumentMatchers.eq(turnId),
                any(), org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(AgentExecutionTurnStatus.SUCCEEDED),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(false))).thenReturn(true);
        when(this.nodeRunRepository.findById(NODE_RUN_ID)).thenAnswer(ignored -> Optional.of(this.nodeRuns.get(NODE_RUN_ID)));
        when(this.workflowRunRepository.findById(WORKFLOW_RUN_ID)).thenAnswer(ignored -> Optional.of(this.workflowRun));
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(new Project(PROJECT_ID, "fixture", "fixture", NOW, NOW)));
        when(connections.findAll(installation)).thenReturn(List.of(connection));
        when(connections.findById(installation, connectionId)).thenReturn(Optional.of(connection));
        var issued = new McpRuntimeGrant[1];
        when(grants.issue(any())).thenAnswer(invocation -> {
            issued[0] = invocation.getArgument(0);
            return new McpRuntimeGrantHandle(issued[0].id(), "joined-grant");
        });
        when(grants.resolve("joined-grant", connectionId)).thenAnswer(ignored -> Optional.of(issued[0]));
        var dispatchAuthorized = new boolean[]{false};
        org.mockito.Mockito.doAnswer(ignored -> {
            dispatchAuthorized[0] = true;
            return null;
        }).when(grants).activateForDispatch(turnId);
        when(grants.admit("joined-grant", connectionId)).thenAnswer(ignored -> dispatchAuthorized[0]);
        var credentials = new McpCredentialService(connections, identity, mock(McpCredentialCipher.class),
                mock(McpOAuthCredentialCipher.class), mock(McpOAuthClient.class), grants, views, CLOCK);
        var gateway = new McpGatewayService(sessions, this.nodeRunRepository, this.workflowRunRepository,
                projects, connections, identity, grants, views, credentials, remote, CLOCK);
        @SuppressWarnings("unchecked") ObjectProvider<McpGatewayService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        var leases = new AgentSessionLeaseService(sessions, provider);
        var joined = new NodeRunLifecycle(this.nodeRunRepository, this.workflowRunRepository,
                this.resolutionRepository, this.inputContentPolicyRegistry, this.coordinator,
                this.completionPolicy, CLOCK, new NodeRunCompletionPersistence(this.nodeRunRepository,
                this.workflowRunRepository, this.completionPolicy, this.coordinator, CLOCK, leases),
                this.completionProcessor, this.executionWorkspaceResolver, this.graphRepository, leases);

        var execution = joined.tryStart(NODE_RUN_ID).orElseThrow();
        assertThat(this.nodeRuns.get(NODE_RUN_ID).status()).isEqualTo(NodeRunStatus.RUNNING);
        assertThat(this.workflowRun.status()).isEqualTo(WorkflowRunStatus.RUNNING);
        assertThat(sessionState[0].status()).isEqualTo(AgentExecutionSessionStatus.CREATING);
        assertThat(turnState[0].status()).isEqualTo(AgentExecutionTurnStatus.STARTING);
        var prepared = new McpExecutionSelectionService(this.workflowRunRepository, connections, identity, gateway)
                .prepare(execution, NOW.plusSeconds(90));
        assertThat(prepared.selection().entries()).hasSize(1);
        assertThat(gateway.authorize("joined-grant", connectionId)).isEqualTo(issued[0]);
        assertThatThrownBy(() -> gateway.call("joined-grant", connectionId, "read", approval.schemaFingerprint(), "{}"))
                .isInstanceOf(McpGatewayAccessException.class);
        verifyNoInteractions(remote);

        gateway.activateForDispatch(claimed[0]);
        assertThat(sessionState[0].status()).isEqualTo(AgentExecutionSessionStatus.CREATING);
        assertThat(turnState[0].status()).isEqualTo(AgentExecutionTurnStatus.STARTING);
        when(remote.call(org.mockito.ArgumentMatchers.eq(connection.endpoint()),
                org.mockito.ArgumentMatchers.eq(McpAuthType.NONE), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("read"), org.mockito.ArgumentMatchers.eq(approval.schemaFingerprint()),
                org.mockito.ArgumentMatchers.eq("{}"), any())).thenAnswer(invocation -> {
            assertThat(invocation.<java.util.function.BooleanSupplier>getArgument(6).getAsBoolean()).isTrue();
            return new McpToolCallResult(false, "[]", null);
        });
        assertThat(gateway.call("joined-grant", connectionId, "read", approval.schemaFingerprint(), "{}").isError()).isFalse();

        leases.persistTurn(claimed[0], "provider-turn");
        assertThat(sessionState[0].status()).isEqualTo(AgentExecutionSessionStatus.ACTIVE);
        assertThat(turnState[0].status()).isEqualTo(AgentExecutionTurnStatus.ACTIVE);
        assertThat(gateway.call("joined-grant", connectionId, "read", approval.schemaFingerprint(), "{}").isError()).isFalse();
        leases.finish(claimed[0], AgentExecutionTurnStatus.SUCCEEDED, null, null, false);
        verify(grants).revokeExecution(turnId);
    }

    @Test
    void invalidSnapshottedModelFailsNodeRunWithoutClaiming() {
        this.nodeRuns.put(NODE_RUN_ID, this.nodeRun(NodeRunStatus.PENDING, null));

        assertThat(this.lifecycle.tryStart(NODE_RUN_ID)).isEmpty();

        assertThat(this.nodeRuns.get(NODE_RUN_ID).status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(this.nodeRuns.get(NODE_RUN_ID).failure().code()).isEqualTo(NodeRunLifecycle.AGENT_MODEL_NOT_CONFIGURED);
        verifyNoInteractions(this.inputContentPolicyRegistry);
    }

    @Test
    void unavailableExecutionWorkspaceFailsNodeBeforeExecutorClaim() {
        this.nodeRuns.put(NODE_RUN_ID, this.nodeRun(NodeRunStatus.PENDING, MODEL));
        when(this.executionWorkspaceResolver.resolve(
                any(), any()))
                .thenThrow(new ExecutionWorkspaceException("Required Forge repository checkout is unavailable."));

        assertThat(this.lifecycle.tryStart(NODE_RUN_ID)).isEmpty();

        assertThat(this.nodeRuns.get(NODE_RUN_ID).status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(this.nodeRuns.get(NODE_RUN_ID).failure().code())
                .isEqualTo(NodeRunLifecycle.EXECUTION_WORKSPACE_UNAVAILABLE);
        assertThat(this.nodeRuns.get(NODE_RUN_ID).failure().message())
                .isEqualTo("Required Forge repository checkout is unavailable.");
        verifyNoInteractions(this.inputContentPolicyRegistry);
    }

    @Test
    void successPersistsOutputAndDelegatesGraphContinuation() {
        this.workflowRun = this.workflowRun(WorkflowRunStatus.RUNNING, NOW, null);
        this.nodeRuns.put(NODE_RUN_ID, this.nodeRun(NodeRunStatus.RUNNING, MODEL));
        final NodeRunOutput output = new NodeRunOutput("{\"ok\":true}");

        final UUID selectedOutputPortId = UUID.fromString("90000000-0000-4000-8000-000000000001");
        this.lifecycle.succeed(NODE_RUN_ID, new AgentExecutionResult(output, selectedOutputPortId));

        assertThat(this.nodeRuns.get(NODE_RUN_ID).status()).isEqualTo(NodeRunStatus.SUCCEEDED);
        assertThat(this.nodeRuns.get(NODE_RUN_ID).output()).isEqualTo(output);
        assertThat(this.nodeRuns.get(NODE_RUN_ID).selectedOutputPortId()).isEqualTo(selectedOutputPortId);
        verify(this.completionProcessor).process(NODE_RUN_ID);
    }

    @Test
    void nonRunningSuccessIsRejected() {
        this.nodeRuns.put(NODE_RUN_ID, this.nodeRun(NodeRunStatus.PENDING, MODEL));

        assertThatThrownBy(() -> this.lifecycle.succeed(NODE_RUN_ID, new AgentExecutionResult(new NodeRunOutput("{\"ok\":true}"), null)))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo(NodeRunLifecycle.LIFECYCLE_CONFLICT);
    }

    @Test
    void failureCompletesRunningNodeRun() {
        this.workflowRun = this.workflowRun(WorkflowRunStatus.RUNNING, NOW, null);
        this.nodeRuns.put(NODE_RUN_ID, this.nodeRun(NodeRunStatus.RUNNING, MODEL));

        this.lifecycle.fail(NODE_RUN_ID, new NodeRunFailure("EXECUTION_FAILED", "Executor failed."));

        assertThat(this.nodeRuns.get(NODE_RUN_ID).status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(this.nodeRuns.get(NODE_RUN_ID).failure()).isEqualTo(new NodeRunFailure("EXECUTION_FAILED", "Executor failed."));
        assertThat(this.workflowRun.status()).isEqualTo(WorkflowRunStatus.FAILED);
    }

    @Test
    void trackedSuccessWithoutSessionLeaseIsRejected() {
        this.workflowRun = this.workflowRun(WorkflowRunStatus.RUNNING, NOW, null);
        this.nodeRuns.put(NODE_RUN_ID, this.trackedNodeRun(NodeRunStatus.RUNNING));

        assertThatThrownBy(() -> this.lifecycle.succeed(
                NODE_RUN_ID,
                new AgentExecutionResult(new NodeRunOutput("{\"ok\":true}"), null)
        )).isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("STALE_AGENT_SESSION_LEASE");
    }

    @Test
    void trackedFailureWithoutSessionLeaseIsRejected() {
        this.workflowRun = this.workflowRun(WorkflowRunStatus.RUNNING, NOW, null);
        this.nodeRuns.put(NODE_RUN_ID, this.trackedNodeRun(NodeRunStatus.RUNNING));

        assertThatThrownBy(() -> this.lifecycle.fail(
                NODE_RUN_ID,
                new NodeRunFailure("EXECUTION_FAILED", "Executor failed.")
        )).isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("STALE_AGENT_SESSION_LEASE");
    }

    @Test
    void terminalWorkflowMakesLateSuccessForCancelledNodeRunANoOp() {
        this.workflowRun = this.workflowRun(WorkflowRunStatus.FAILED, NOW, NOW);
        this.nodeRuns.put(NODE_RUN_ID, this.nodeRun(NodeRunStatus.CANCELLED, MODEL));

        this.lifecycle.succeed(NODE_RUN_ID, new AgentExecutionResult(new NodeRunOutput("{\"late\":true}"), null));

        assertThat(this.nodeRuns.get(NODE_RUN_ID).status()).isEqualTo(NodeRunStatus.CANCELLED);
        verifyNoInteractions(this.completionProcessor);
    }

    @Test
    void terminalWorkflowMakesLateFailureForCancelledNodeRunANoOp() {
        this.workflowRun = this.workflowRun(WorkflowRunStatus.FAILED, NOW, NOW);
        this.nodeRuns.put(NODE_RUN_ID, this.nodeRun(NodeRunStatus.CANCELLED, MODEL));

        this.lifecycle.fail(NODE_RUN_ID, new NodeRunFailure("LATE_FAILURE", "Late failure."));

        assertThat(this.nodeRuns.get(NODE_RUN_ID).status()).isEqualTo(NodeRunStatus.CANCELLED);
    }

    @Test
    void cancelledWorkflowAbsorbsLateTrackedSuccessBeforeCheckingFencedLease() {
        this.workflowRun = this.workflowRun(WorkflowRunStatus.CANCELLED, NOW, NOW);
        this.nodeRuns.put(NODE_RUN_ID, this.trackedNodeRun(NodeRunStatus.CANCELLED));

        this.lifecycle.succeed(
                NODE_RUN_ID,
                new AgentExecutionResult(new NodeRunOutput("{\"late\":true}"), null),
                this.sessionClaim()
        );

        assertThat(this.nodeRuns.get(NODE_RUN_ID).status()).isEqualTo(NodeRunStatus.CANCELLED);
        verifyNoInteractions(this.sessionLeaseService, this.completionProcessor);
    }

    @Test
    void cancelledWorkflowAbsorbsLateTrackedFailureBeforeCheckingFencedLease() {
        this.workflowRun = this.workflowRun(WorkflowRunStatus.CANCELLED, NOW, NOW);
        this.nodeRuns.put(NODE_RUN_ID, this.trackedNodeRun(NodeRunStatus.CANCELLED));

        this.lifecycle.fail(
                NODE_RUN_ID,
                new NodeRunFailure("LATE_FAILURE", "Late failure."),
                this.sessionClaim()
        );

        assertThat(this.nodeRuns.get(NODE_RUN_ID).status()).isEqualTo(NodeRunStatus.CANCELLED);
        verifyNoInteractions(this.sessionLeaseService);
    }

    @Test
    void activeWorkflowRejectsSuccessForCancelledNodeRun() {
        this.workflowRun = this.workflowRun(WorkflowRunStatus.RUNNING, NOW, null);
        this.nodeRuns.put(NODE_RUN_ID, this.nodeRun(NodeRunStatus.CANCELLED, MODEL));

        assertThatThrownBy(() -> this.lifecycle.succeed(NODE_RUN_ID, new AgentExecutionResult(new NodeRunOutput("{\"late\":true}"), null)))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo(NodeRunLifecycle.LIFECYCLE_CONFLICT);
    }

    @Test
    void activeWorkflowRejectsFailureForCancelledNodeRun() {
        this.workflowRun = this.workflowRun(WorkflowRunStatus.RUNNING, NOW, null);
        this.nodeRuns.put(NODE_RUN_ID, this.nodeRun(NodeRunStatus.CANCELLED, MODEL));

        assertThatThrownBy(() -> this.lifecycle.fail(
                NODE_RUN_ID, new NodeRunFailure("LATE_FAILURE", "Late failure.")))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo(NodeRunLifecycle.LIFECYCLE_CONFLICT);
    }

    private void stubRepositories() {
        lenient().when(this.nodeRunRepository.findWorkflowRunIdById(any())).thenAnswer(invocation -> Optional.ofNullable(this.nodeRuns.get(invocation.getArgument(0)))
                .map(NodeRun::workflowRunId));
        lenient().when(this.nodeRunRepository.findByIdForUpdate(any())).thenAnswer(invocation -> Optional.ofNullable(this.nodeRuns.get(invocation.getArgument(0))));
        lenient().when(this.nodeRunRepository.findByWorkflowRunId(any())).thenAnswer(invocation -> this.nodeRuns.values().stream()
                .filter(nodeRun -> invocation.getArgument(0).equals(nodeRun.workflowRunId()))
                .toList());
        lenient().when(this.nodeRunRepository.save(any())).thenAnswer(invocation -> {
            final NodeRun nodeRun = invocation.getArgument(0);
            this.nodeRuns.put(nodeRun.id(), nodeRun);
            return nodeRun;
        });
        lenient().when(this.nodeRunRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            final NodeRun nodeRun = invocation.getArgument(0);
            this.nodeRuns.put(nodeRun.id(), nodeRun);
            return nodeRun;
        });
        lenient().when(this.workflowRunRepository.findByIdForUpdate(any())).thenAnswer(invocation -> Optional.ofNullable(this.workflowRun));
        lenient().when(this.workflowRunRepository.saveLifecycle(any())).thenAnswer(invocation -> {
            this.workflowRun = invocation.getArgument(0);
            return this.workflowRun;
        });
        lenient().when(this.completionPolicy.evaluate(any())).thenReturn(new FailedWorkflowDecision());
        lenient().when(this.coordinator.completionDecisionHandler(any())).thenAnswer(invocation -> new WorkflowCompletionDecisionHandler() {
            @Override
            public void handle(final RunningWorkflowDecision decision) {
            }

            @Override
            public void handle(final SuccessfulWorkflowDecision decision) {
                NodeRunLifecycleTest.this.workflowRun = NodeRunLifecycleTest.this.workflowRun(WorkflowRunStatus.SUCCEEDED, NOW, NOW);
            }

            @Override
            public void handle(final FailedWorkflowDecision decision) {
                NodeRunLifecycleTest.this.workflowRun = NodeRunLifecycleTest.this.workflowRun(WorkflowRunStatus.FAILED, NOW, NOW);
            }
        });
    }

    private WorkflowRun workflowRun(final WorkflowRunStatus status, final Instant startedAt, final Instant finishedAt) {
        return new WorkflowRun(
                WORKFLOW_RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                "Review auth changes.",
                status,
                List.of(),
                java.util.List.of(),
                java.util.List.of(),
                null,
                null,
                null,
                Instant.EPOCH,
                startedAt,
                finishedAt,
                java.util.List.of()
        );
    }

    private NodeRun nodeRun(final NodeRunStatus status, final NodeRunExecutionModel executionModel) {
        return new NodeRun(
                NODE_RUN_ID,
                WORKFLOW_RUN_ID,
                UUID.randomUUID(),
                AGENT_ID,
                "Snapshot Agent",
                "Snapshot instructions.",
                SCHEMA,
                NodeInputMode.DEPENDENCIES_ONLY,
                new NodePosition(1.0, 2.0),
                FRAME_ID,
                null,
                null,
                null,
                null,
                status,
                null,
                null,
                executionModel,
                Instant.EPOCH,
                null,
                null,
                null
        );
    }

    private NodeRun trackedNodeRun(final NodeRunStatus status) {
        final NodeRun legacy = this.nodeRun(status, MODEL);
        return new NodeRun(
                legacy.id(), legacy.workflowRunId(), legacy.sourceNodeId(), legacy.sourceAgentId(),
                legacy.agentName(), legacy.agentInstructions(), legacy.agentOutputSchema(), legacy.inputMode(),
                legacy.position(), legacy.executionFrameId(), legacy.enteredViaInputPortId(),
                legacy.activationFrameId(), legacy.selectedOutputPortId(), legacy.routingCompletedAt(),
                legacy.status(), legacy.output(), legacy.failure(), legacy.executionModel(), legacy.createdAt(),
                legacy.startedAt(), legacy.finishedAt(), legacy.repositoryId(),
                NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE, 1
        );
    }

    private AgentSessionExecutionClaim sessionClaim() {
        return new AgentSessionExecutionClaim(
                UUID.randomUUID(), UUID.randomUUID(), NODE_RUN_ID, "worker-a", 1,
                NOW.plusSeconds(30), "thread-1", "codex",
                NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE, "0.153.2"
        );
    }

}
