package com.sitionix.forgeagent.application.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.application.runtime.NodeExecutionClaim;
import com.sitionix.forgeagent.domain.model.AgentSessionExecutionClaim;
import com.sitionix.forgeagent.domain.model.McpAllowedTool;
import com.sitionix.forgeagent.domain.model.McpAuthType;
import com.sitionix.forgeagent.domain.model.McpConnection;
import com.sitionix.forgeagent.domain.model.McpProjectAccess;
import com.sitionix.forgeagent.domain.model.McpRuntimeGrantHandle;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.exception.McpProbeException;
import com.sitionix.forgeagent.domain.port.McpConnectionRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.net.URI;
import java.time.Instant;
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
                false, NOW, NOW, null, null);
    }
}
