package com.sitionix.forgeagent.application.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;

import com.sitionix.forgeagent.application.runtime.NodeExecutionClaim;
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
import com.sitionix.forgeagent.domain.model.McpRuntimeGrantHandle;
import com.sitionix.forgeagent.domain.model.NodeContextMode;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.exception.McpProbeException;
import com.sitionix.forgeagent.domain.port.McpConnectionRepository;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.McpRuntimeGrantRepository;
import com.sitionix.forgeagent.domain.port.McpRuntimeToolView;
import com.sitionix.forgeagent.domain.port.McpCredentialCipher;
import com.sitionix.forgeagent.domain.port.McpOAuthCredentialCipher;
import com.sitionix.forgeagent.domain.port.McpOAuthClient;
import com.sitionix.forgeagent.domain.port.McpRemoteToolClient;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class McpExecutionSelectionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private final UUID installation = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID otherProjectId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();
    private final AgentSessionExecutionClaim session = new AgentSessionExecutionClaim(
            UUID.randomUUID(), UUID.randomUUID(), nodeId, "worker", 4, NOW.plusSeconds(60), null, "codex");
    private final WorkflowRunRepository workflows = Mockito.mock(WorkflowRunRepository.class);
    private final McpConnectionRepository connections = Mockito.mock(McpConnectionRepository.class);
    private final McpGatewayService gateway = Mockito.mock(McpGatewayService.class);
    private final McpExecutionSelectionService service = new McpExecutionSelectionService(
            workflows, connections, () -> installation, gateway);

    @Test void selectedProjectGetsOnlyItsEnabledApprovedConnections() {
        var allowed = connection(UUID.randomUUID(), true, McpProjectAccess.selected(Set.of(projectId)), true);
        var foreign = connection(UUID.randomUUID(), true, McpProjectAccess.selected(Set.of(otherProjectId)), true);
        var disabled = connection(UUID.randomUUID(), false, McpProjectAccess.all(), true);
        var empty = connection(UUID.randomUUID(), true, McpProjectAccess.all(), false);
        when(workflows.findById(workflowId)).thenReturn(Optional.of(workflow(projectId)));
        when(connections.findAll(installation)).thenReturn(List.of(foreign, empty, allowed, disabled));
        when(gateway.issue(session, NOW.plusSeconds(90), allowed.id()))
                .thenReturn(new McpRuntimeGrantHandle(UUID.randomUUID(), "synthetic-grant"));

        var prepared = service.prepare(claim(session), NOW.plusSeconds(90));

        assertThat(prepared.selection().entries()).extracting(entry -> entry.connectionId())
                .containsExactly(allowed.id());
        assertThat(prepared.selection().entries().getFirst().alias())
                .isEqualTo("forge_" + allowed.id().toString().replace("-", ""));
        assertThat(prepared.launchGrants().toString()).doesNotContain("synthetic-grant");
        verify(gateway).issue(session, NOW.plusSeconds(90), allowed.id());
        service.revoke(session);
        verify(gateway).revokeExecution(session.turnId());
    }

    @Test void untrackedLegacyRunWithAvailableConnectionFailsVisibly() {
        var allowed = connection(UUID.randomUUID(), true, McpProjectAccess.all(), true);
        when(workflows.findById(workflowId)).thenReturn(Optional.of(workflow(projectId)));
        when(connections.findAll(installation)).thenReturn(List.of(allowed));
        assertThatThrownBy(() -> service.prepare(claim(null), NOW.plusSeconds(90)))
                .hasMessage("MCP execution requires a tracked session");
    }

    @Test void dialogueTurnsRecheckCurrentPermissionsRatherThanReusingToolSelection() {
        var allowed = connection(UUID.randomUUID(),true,McpProjectAccess.selected(Set.of(projectId)),true);
        var first = new AgentSessionExecutionClaim(session.sessionId(),UUID.randomUUID(),nodeId,"worker",4,
                NOW.plusSeconds(60),"thread","codex",NodeContextMode.DIALOGUE_WITHIN_NODE_RUN);
        var next = new AgentSessionExecutionClaim(session.sessionId(),UUID.randomUUID(),nodeId,"worker",5,
                NOW.plusSeconds(60),"thread","codex",NodeContextMode.DIALOGUE_WITHIN_NODE_RUN);
        when(workflows.findById(workflowId)).thenReturn(Optional.of(workflow(projectId)));
        when(connections.findAll(installation)).thenReturn(List.of(allowed),List.of());
        when(gateway.issue(first,NOW.plusSeconds(90),allowed.id()))
                .thenReturn(new McpRuntimeGrantHandle(UUID.randomUUID(),"first-turn-grant"));
        assertThat(service.prepare(claim(first),NOW.plusSeconds(90)).selection().entries()).hasSize(1);
        assertThat(service.prepare(claim(next),NOW.plusSeconds(90)).selection().entries()).isEmpty();
        verify(gateway,Mockito.never()).issue(org.mockito.ArgumentMatchers.eq(next),any(),any());
        verify(connections,Mockito.times(2)).findAll(installation);
    }

    @Test void connectedLlmAccountDoesNotGrantForeignProjectsOrUnapprovedTools() {
        var provider = mock(com.sitionix.forgeagent.domain.port.LlmAuthorizationGateway.Session.class);
        when(provider.healthy()).thenReturn(true);
        when(provider.drainEvents()).thenReturn(List.of());
        when(provider.readAccount(org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(
                new com.sitionix.forgeagent.domain.port.LlmAuthorizationGateway.Account(true, "forge@example.test", "plus"));
        try (var auth = new com.sitionix.forgeagent.application.llm.LlmAuthorizationService(() -> provider, Clock.systemUTC())) {
            var gate = new com.sitionix.forgeagent.application.llm.ForgeCodexAuthorizationGate(auth);
            var lease = gate.requireAuthorized();
            var allowed = connection(UUID.randomUUID(), true, McpProjectAccess.selected(Set.of(projectId)), true);
            var foreign = connection(UUID.randomUUID(), true, McpProjectAccess.selected(Set.of(otherProjectId)), true);
            var unapproved = connection(UUID.randomUUID(), true, McpProjectAccess.selected(Set.of(projectId)), false);
            when(workflows.findById(workflowId)).thenReturn(Optional.of(workflow(projectId)));
            when(connections.findAll(installation)).thenReturn(List.of(allowed, foreign, unapproved));
            when(gateway.issue(session, NOW.plusSeconds(90), allowed.id()))
                    .thenReturn(new McpRuntimeGrantHandle(UUID.randomUUID(), "synthetic-grant"));

            var prepared = service.prepare(claim(session), NOW.plusSeconds(90));

            assertThat(prepared.selection().entries()).extracting(entry -> entry.connectionId()).containsExactly(allowed.id());
            assertThat(prepared.selection().entries().getFirst().tools()).isEqualTo(allowed.allowedTools());
            verify(gateway, Mockito.never()).issue(any(), any(), org.mockito.ArgumentMatchers.eq(foreign.id()));
            verify(gateway, Mockito.never()).issue(any(), any(), org.mockito.ArgumentMatchers.eq(unapproved.id()));
            gate.release(lease);
            when(provider.readAccount(false)).thenReturn(new com.sitionix.forgeagent.domain.port.LlmAuthorizationGateway.Account(false, null, null));
            auth.logout();
            // LLM logout cannot edit MCP configuration or revoke its independent OAuth credentials.
            verify(connections).findAll(installation);
            Mockito.verifyNoMoreInteractions(connections);
        }
    }

    @Test void unavailableConnectionDoesNotHideAnotherApprovedConnection() {
        var broken = connection(UUID.fromString("00000000-0000-4000-8000-000000000001"), true,
                McpProjectAccess.all(), true);
        var working = connection(UUID.fromString("00000000-0000-4000-8000-000000000002"), true,
                McpProjectAccess.all(), true);
        when(workflows.findById(workflowId)).thenReturn(Optional.of(workflow(projectId)));
        when(connections.findAll(installation)).thenReturn(List.of(working, broken));
        when(gateway.issue(session, NOW.plusSeconds(90), broken.id()))
                .thenThrow(new McpProbeException(McpProbeException.Reason.UNAVAILABLE));
        when(gateway.issue(session, NOW.plusSeconds(90), working.id()))
                .thenReturn(new McpRuntimeGrantHandle(UUID.randomUUID(), "working-grant"));

        var prepared = service.prepare(claim(session), NOW.plusSeconds(90));

        assertThat(prepared.selection().entries()).extracting(entry -> entry.connectionId())
                .containsExactly(working.id());
        assertThat(prepared.selection().diagnostics()).extracting(diagnostic -> diagnostic.connectionId())
                .containsExactly(broken.id());
    }

    @Test void oauthReconnectAndRefreshOutageDoNotHideHealthyApprovedConnection() {
        for (var failure : List.of(
                com.sitionix.forgeagent.domain.exception.McpOAuthException.reconnect(),
                com.sitionix.forgeagent.domain.exception.McpOAuthException.unavailable())) {
            var broken = connection(UUID.fromString("00000000-0000-4000-8000-000000000001"), true,
                    McpProjectAccess.all(), true);
            var working = connection(UUID.fromString("00000000-0000-4000-8000-000000000002"), true,
                    McpProjectAccess.all(), true);
            when(workflows.findById(workflowId)).thenReturn(Optional.of(workflow(projectId)));
            when(connections.findAll(installation)).thenReturn(List.of(working, broken));
            org.mockito.Mockito.doThrow(failure).when(gateway)
                    .issue(session, NOW.plusSeconds(90), broken.id());
            when(gateway.issue(session, NOW.plusSeconds(90), working.id()))
                    .thenReturn(new McpRuntimeGrantHandle(UUID.randomUUID(), "healthy-oauth-grant"));

            var prepared = service.prepare(claim(session), NOW.plusSeconds(90));

            assertThat(prepared.selection().entries()).extracting(entry -> entry.connectionId())
                    .containsExactly(working.id());
            assertThat(prepared.selection().diagnostics()).extracting(diagnostic -> diagnostic.connectionId())
                    .containsExactly(broken.id());
            assertThat(prepared.launchGrants().tokens())
                    .containsOnlyKeys("forge_" + working.id().toString().replace("-", ""));
        }
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).revokeExecution(session.turnId());
    }

    @Test void unexpectedIssuanceFailureRevokesAlreadyIssuedGrantWithoutLeakingCause() {
        var first = connection(UUID.fromString("00000000-0000-4000-8000-000000000001"), true,
                McpProjectAccess.all(), true);
        var second = connection(UUID.fromString("00000000-0000-4000-8000-000000000002"), true,
                McpProjectAccess.all(), true);
        when(workflows.findById(workflowId)).thenReturn(Optional.of(workflow(projectId)));
        when(connections.findAll(installation)).thenReturn(List.of(second, first));
        when(gateway.issue(session, NOW.plusSeconds(90), first.id()))
                .thenReturn(new McpRuntimeGrantHandle(UUID.randomUUID(), "synthetic-grant"));
        when(gateway.issue(session, NOW.plusSeconds(90), second.id()))
                .thenThrow(new IllegalStateException("synthetic-secret-canary"));

        assertThatThrownBy(() -> service.prepare(claim(session), NOW.plusSeconds(90)))
                .hasMessage("MCP execution preparation failed")
                .hasNoCause();
        verify(gateway).revokeExecution(session.turnId());
    }

    @Test void globalConnectionIssuesDistinctTokensForDistinctProjectExecutions() {
        var global = connection(UUID.randomUUID(), true, McpProjectAccess.all(), true);
        var secondWorkflow = UUID.randomUUID();
        var secondNode = UUID.randomUUID();
        var secondSession = new AgentSessionExecutionClaim(UUID.randomUUID(), UUID.randomUUID(), secondNode,
                "worker-two", 5, NOW.plusSeconds(60), null, "codex");
        when(workflows.findById(workflowId)).thenReturn(Optional.of(workflow(projectId)));
        when(workflows.findById(secondWorkflow)).thenReturn(Optional.of(new WorkflowRun(
                secondWorkflow, otherProjectId, UUID.randomUUID(), null, "workflow", "input",
                WorkflowRunStatus.RUNNING, List.of(), List.of(), List.of(), null, null, null,
                NOW, NOW, null, List.of())));
        when(connections.findAll(installation)).thenReturn(List.of(global));
        when(gateway.issue(session, NOW.plusSeconds(90), global.id()))
                .thenReturn(new McpRuntimeGrantHandle(UUID.randomUUID(), "grant-project-a"));
        when(gateway.issue(secondSession, NOW.plusSeconds(90), global.id()))
                .thenReturn(new McpRuntimeGrantHandle(UUID.randomUUID(), "grant-project-b"));

        var first = service.prepare(claim(session), NOW.plusSeconds(90));
        var second = service.prepare(new NodeExecutionClaim(secondWorkflow, secondNode, UUID.randomUUID(),
                null, null, null, null, null, null, List.of(), null, secondSession), NOW.plusSeconds(90));

        assertThat(first.selection().entries()).extracting(entry -> entry.connectionId())
                .containsExactly(global.id());
        assertThat(second.selection().entries()).extracting(entry -> entry.connectionId())
                .containsExactly(global.id());
        assertThat(first.launchGrants().tokens().values()).containsExactly("grant-project-a");
        assertThat(second.launchGrants().tokens().values()).containsExactly("grant-project-b");
    }

    @Test void untrackedLegacyRunWithoutEligibleConnectionKeepsNoMcpBehavior() {
        when(workflows.findById(workflowId)).thenReturn(Optional.of(workflow(projectId)));
        when(connections.findAll(installation)).thenReturn(List.of());

        var prepared = service.prepare(claim(null), NOW.plusSeconds(90));

        assertThat(prepared.selection().entries()).isEmpty();
        assertThat(prepared.launchGrants().isEmpty()).isTrue();
    }

    @Test void realGatewayPreparesFreshAndResumingTrackedClaimsBeforeProviderTurnStarts() {
        for (var status : List.of(AgentExecutionSessionStatus.CREATING, AgentExecutionSessionStatus.RESUMING)) {
            var allowed = connection(UUID.randomUUID(), true, McpProjectAccess.selected(Set.of(projectId)), true);
            var sessionRepository = mock(AgentExecutionSessionRepository.class);
            var nodeRepository = mock(NodeRunRepository.class);
            var projectRepository = mock(ProjectRepository.class);
            var grants = mock(McpRuntimeGrantRepository.class);
            var views = mock(McpRuntimeToolView.class);
            var remote = mock(McpRemoteToolClient.class);
            var clock = Clock.fixed(NOW, ZoneOffset.UTC);
            var tracked = new AgentExecutionSession(session.sessionId(), workflowId, UUID.randomUUID(),
                    UUID.randomUUID(), null, "codex", null, null, NodeContextMode.FRESH_EACH_NODE_RUN,
                    status, null, nodeId, session.leaseOwnerId(), session.leaseToken(), NOW.plusSeconds(60),
                    null, null, NOW, NOW, null, null);
            var turn = new AgentExecutionTurn(session.turnId(), session.sessionId(), nodeId, null, 1,
                    AgentExecutionTurnStatus.STARTING, null, null, null, null, null, NOW, null, NOW, NOW);
            when(sessionRepository.findSession(session.sessionId())).thenReturn(Optional.of(tracked));
            when(sessionRepository.findByExecutionTurnId(session.turnId())).thenReturn(Optional.of(new AgentExecutionAllocation(tracked, turn)));
            when(sessionRepository.lockCurrentLease(session.sessionId(), session.leaseOwnerId(), session.leaseToken()))
                    .thenReturn(true);
            when(nodeRepository.findById(nodeId)).thenReturn(Optional.of(new NodeRun(nodeId, workflowId,
                    UUID.randomUUID(), UUID.randomUUID(), "agent", "instructions", null,
                    NodeInputMode.DEPENDENCIES_ONLY, new NodePosition(1, 1), UUID.randomUUID(),
                    null, null, null, null, NodeRunStatus.RUNNING, null, null, null, NOW, NOW, null, null)));
            when(workflows.findById(workflowId)).thenReturn(Optional.of(workflow(projectId)));
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(new Project(projectId, "fixture", "fixture", NOW, NOW)));
            when(connections.findAll(installation)).thenReturn(List.of(allowed));
            when(connections.findById(installation, allowed.id())).thenReturn(Optional.of(allowed));
            when(grants.issue(any())).thenAnswer(invocation ->
                    new McpRuntimeGrantHandle(invocation.<com.sitionix.forgeagent.domain.model.McpRuntimeGrant>getArgument(0).id(),
                            "pre-turn-grant"));
            var credentials = new McpCredentialService(connections, () -> installation,
                    mock(McpCredentialCipher.class), mock(McpOAuthCredentialCipher.class),
                    mock(McpOAuthClient.class), grants, views, clock);
            var realGateway = new McpGatewayService(sessionRepository, nodeRepository, workflows,
                    projectRepository, connections, () -> installation, grants, views, credentials, remote, clock);

            assertThat(tracked.status()).isEqualTo(status);
            assertThat(turn.status()).isEqualTo(AgentExecutionTurnStatus.STARTING);
            var prepared = new McpExecutionSelectionService(workflows, connections, () -> installation, realGateway)
                    .prepare(claim(session), NOW.plusSeconds(90));
            assertThat(prepared.selection().entries()).extracting(entry -> entry.connectionId())
                    .containsExactly(allowed.id());
            assertThat(prepared.launchGrants().tokens().values()).containsExactly("pre-turn-grant");
            verify(views).prepare(any(), org.mockito.ArgumentMatchers.isNull());
        }
    }

    private NodeExecutionClaim claim(AgentSessionExecutionClaim sessionClaim) {
        return new NodeExecutionClaim(workflowId, nodeId, UUID.randomUUID(), null, null, null,
                null, null, null, List.of(), null, sessionClaim);
    }

    private WorkflowRun workflow(UUID project) {
        return new WorkflowRun(workflowId, project, UUID.randomUUID(), null, "workflow", "input",
                WorkflowRunStatus.RUNNING, List.of(), List.of(), List.of(), null, null, null,
                NOW, NOW, null, List.of());
    }

    private McpConnection connection(UUID id, boolean enabled, McpProjectAccess access, boolean withTool) {
        return new McpConnection(id, installation, "fixture", URI.create("https://example.org/mcp"),
                McpAuthType.NONE, enabled, access,
                withTool ? Set.of(new McpAllowedTool("echo", "sha256:" + "a".repeat(64))) : Set.of(),
                false, NOW, NOW, null, null, null, null);
    }
}
