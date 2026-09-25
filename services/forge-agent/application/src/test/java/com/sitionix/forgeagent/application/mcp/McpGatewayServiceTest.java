package com.sitionix.forgeagent.application.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpGatewayServiceTest {
    private final UUID installation = UUID.randomUUID(), sessionId = UUID.randomUUID(), turnId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID(), workflowId = UUID.randomUUID(), projectId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-25T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final McpAllowedTool tool = new McpAllowedTool("search", "sha256:" + "a".repeat(64));
    private final AgentSessionExecutionClaim claim = new AgentSessionExecutionClaim(sessionId, turnId, nodeId,
            "owner", 7L, now.plusSeconds(60), null, "codex");
    private final AgentExecutionSessionRepository sessions = mock(AgentExecutionSessionRepository.class);
    private final NodeRunRepository nodes = mock(NodeRunRepository.class);
    private final WorkflowRunRepository workflows = mock(WorkflowRunRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final McpConnectionRepository connections = mock(McpConnectionRepository.class);
    private final McpRuntimeGrantRepository grants = mock(McpRuntimeGrantRepository.class);
    private final McpRuntimeToolView views = mock(McpRuntimeToolView.class);
    private final McpCredentialCipher cipher = mock(McpCredentialCipher.class);
    private final McpRemoteToolClient remote = mock(McpRemoteToolClient.class);
    private final McpGatewayService service = new McpGatewayService(sessions, nodes, workflows, projects,
            connections, () -> installation, grants, views, cipher, remote, clock);
    private final AgentExecutionTurn turn = new AgentExecutionTurn(turnId, sessionId, nodeId, null, 1,
            AgentExecutionTurnStatus.ACTIVE, null, null, null, null, null, now, null, now, now);
    private final McpEncryptedCredential encrypted = new McpEncryptedCredential("test", new byte[]{1, 2, 3});

    @BeforeEach void activeExecution() {
        when(grants.admit(anyString(), any())).thenReturn(true);
        when(sessions.findSession(sessionId)).thenReturn(Optional.of(session(nodeId, now.plusSeconds(60))));
        when(sessions.findByNodeRunId(nodeId)).thenReturn(Optional.of(new AgentExecutionAllocation(session(nodeId, now.plusSeconds(60)), turn)));
        when(sessions.lockCurrentLease(sessionId, "owner", 7L)).thenReturn(true);
        when(nodes.findById(nodeId)).thenReturn(Optional.of(new NodeRun(nodeId, workflowId, UUID.randomUUID(),
                UUID.randomUUID(), "agent", "instructions", null, NodeInputMode.DEPENDENCIES_ONLY,
                new NodePosition(1, 1), UUID.randomUUID(), null, null, null, null,
                NodeRunStatus.RUNNING, null, null, null, now, now, null, null)));
        when(workflows.findById(workflowId)).thenReturn(Optional.of(new WorkflowRun(workflowId, projectId,
                UUID.randomUUID(), null, "workflow", "input", WorkflowRunStatus.RUNNING, List.of(),
                List.of(), List.of(), null, null, null, now, now, null, List.of())));
        when(projects.findById(projectId)).thenReturn(Optional.of(new Project(projectId, "test", "test", now, now)));
        when(connections.findById(installation, connectionId)).thenReturn(Optional.of(connection(true, McpProjectAccess.all())));
        when(connections.credential(installation, connectionId)).thenReturn(Optional.of(encrypted));
    }

    @Test void issueUsesTrustedProjectAndErasesTemporaryCredential() {
        byte[] plaintext = {42, 43};
        when(cipher.decrypt(installation, connectionId, "credential", encrypted)).thenReturn(plaintext);
        when(grants.issue(any())).thenAnswer(call -> new McpRuntimeGrantHandle(call.<McpRuntimeGrant>getArgument(0).id(), "synthetic-token"));
        var handle = service.issue(claim, now.plusSeconds(120), connectionId);
        var issued = org.mockito.ArgumentCaptor.forClass(McpRuntimeGrant.class);
        verify(grants).issue(issued.capture());
        assertThat(handle.token()).isEqualTo("synthetic-token");
        assertThat(issued.getValue().projectId()).isEqualTo(projectId);
        assertThat(issued.getValue().deadline()).isEqualTo(now.plusSeconds(120));
        assertThat(issued.getValue().tools()).containsExactly(tool);
        verify(views).prepare(eq(issued.getValue()), same(plaintext));
        assertThat(plaintext).containsOnly((byte) 0);
    }

    @Test void foreignNodeAndEmptySelectedProjectsDenyBeforeDiscovery() {
        when(sessions.findSession(sessionId)).thenReturn(Optional.of(session(UUID.randomUUID(), now.plusSeconds(60))));
        assertThatThrownBy(() -> service.issue(claim, now.plusSeconds(120), connectionId))
                .isInstanceOf(McpGatewayAccessException.class);
        when(sessions.findSession(sessionId)).thenReturn(Optional.of(session(nodeId, now.plusSeconds(60))));
        when(connections.findById(installation, connectionId))
                .thenReturn(Optional.of(connection(true, McpProjectAccess.selected(Set.of()))));
        assertThatThrownBy(() -> service.issue(claim, now.plusSeconds(120), connectionId))
                .isInstanceOf(McpGatewayAccessException.class);
        verifyNoInteractions(views, remote);
    }

    @Test void disabledOrExpiredLeaseDeniesWithoutUpstreamCall() {
        when(connections.findById(installation, connectionId))
                .thenReturn(Optional.of(connection(false, McpProjectAccess.all())));
        assertThatThrownBy(() -> service.issue(claim, now.plusSeconds(120), connectionId))
                .isInstanceOf(McpGatewayAccessException.class);
        when(connections.findById(installation, connectionId))
                .thenReturn(Optional.of(connection(true, McpProjectAccess.all())));
        when(sessions.findSession(sessionId)).thenReturn(Optional.of(session(nodeId, now)));
        assertThatThrownBy(() -> service.issue(claim, now.plusSeconds(120), connectionId))
                .isInstanceOf(McpGatewayAccessException.class);
        verifyNoInteractions(views, remote);
    }

    @Test void activeGrantAllowsOnlySnapshottedToolAndUsesTemporaryCredential() {
        var grant = issuedGrant();
        when(grants.resolve("synthetic-token", connectionId)).thenReturn(Optional.of(grant));
        byte[] callCredential = {8, 9};
        when(cipher.decrypt(installation, connectionId, "credential", encrypted)).thenReturn(callCredential);
        var result = new McpToolCallResult(false, "[]", null);
        when(remote.call(eq(grant.endpoint()), eq(grant.authType()), same(callCredential), eq(tool.name()),
                eq(tool.schemaFingerprint()), eq("{}"), any())).thenAnswer(invocation -> {
            assertThat(invocation.<java.util.function.BooleanSupplier>getArgument(6).getAsBoolean()).isTrue();
            return result;
        });
        assertThat(service.call("synthetic-token", connectionId, tool.name(), tool.schemaFingerprint(), "{}"))
                .isEqualTo(result);
        assertThat(callCredential).containsOnly((byte) 0);
        assertThatThrownBy(() -> service.call("synthetic-token", connectionId, "write", tool.schemaFingerprint(), "{}"))
                .isInstanceOf(McpGatewayAccessException.class);
        verify(remote, times(1)).call(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void disableThenReenableDoesNotReviveOldGrant() {
        var grant = issuedGrant();
        when(grants.resolve("synthetic-token", connectionId)).thenReturn(Optional.of(grant));
        when(connections.findById(installation, connectionId))
                .thenReturn(Optional.of(connection(false, McpProjectAccess.all())));
        assertThatThrownBy(() -> service.authorize("synthetic-token", connectionId))
                .isInstanceOf(McpGatewayAccessException.class);
        verify(grants).remove(grant.id());
        verify(views).remove(grant.id());
        verifyNoInteractions(remote);
    }

    @Test void changedApprovalOrProjectPolicyDeniesBeforeRemoteCall() {
        var grant = issuedGrant();
        when(grants.resolve("synthetic-token", connectionId)).thenReturn(Optional.of(grant));
        when(connections.findById(installation, connectionId)).thenReturn(Optional.of(new McpConnection(
                connectionId, installation, "test", grant.endpoint(), McpAuthType.BEARER, true,
                McpProjectAccess.all(), Set.of(), true, now, now, null, null)));
        assertThatThrownBy(() -> service.call("synthetic-token", connectionId,
                tool.name(), tool.schemaFingerprint(), "{}"))
                .isInstanceOf(McpGatewayAccessException.class);
        when(connections.findById(installation, connectionId))
                .thenReturn(Optional.of(connection(true, McpProjectAccess.selected(Set.of()))));
        assertThatThrownBy(() -> service.authorize("synthetic-token", connectionId))
                .isInstanceOf(McpGatewayAccessException.class);
        verifyNoInteractions(remote);
    }

    @Test void credentialReplacementBetweenPolicyCheckAndCallNeverUsesNewSecretWithOldGrant() {
        var grant = issuedGrant();
        when(grants.resolve("synthetic-token", connectionId)).thenReturn(Optional.of(grant));
        var changed = new McpEncryptedCredential("test", new byte[]{9, 9, 9});
        when(connections.credential(installation, connectionId))
                .thenReturn(Optional.of(encrypted), Optional.of(changed));
        assertThatThrownBy(() -> service.call("synthetic-token", connectionId,
                tool.name(), tool.schemaFingerprint(), "{}"))
                .isInstanceOf(McpGatewayAccessException.class);
        verifyNoInteractions(remote);
    }

    @Test void exhaustedGrantCapacityStopsBeforeUpstreamDiscoveryOrDecryption() {
        when(grants.issue(any())).thenThrow(new IllegalStateException("MCP runtime grant capacity reached"));
        assertThatThrownBy(() -> service.issue(claim, now.plusSeconds(120), connectionId))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(views, cipher, remote);
    }

    private McpRuntimeGrant issuedGrant() {
        when(cipher.decrypt(installation, connectionId, "credential", encrypted)).thenReturn(new byte[]{42});
        var captured = new McpRuntimeGrant[1];
        when(grants.issue(any())).thenAnswer(call -> {
            captured[0] = call.getArgument(0);
            return new McpRuntimeGrantHandle(captured[0].id(), "synthetic-token");
        });
        service.issue(claim, now.plusSeconds(120), connectionId);
        return captured[0];
    }

    private McpConnection connection(boolean enabled, McpProjectAccess access) {
        return new McpConnection(connectionId, installation, "test", URI.create("https://example.org/mcp"),
                McpAuthType.BEARER, enabled, access, Set.of(tool), true, now, now, null, null);
    }

    private AgentExecutionSession session(UUID activeNode, Instant leaseExpiry) {
        return new AgentExecutionSession(sessionId, workflowId, UUID.randomUUID(), UUID.randomUUID(), null,
                "codex", null, null, NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE,
                AgentExecutionSessionStatus.ACTIVE, null, activeNode, "owner", 7L, leaseExpiry,
                null, null, now, now, null, null);
    }
}
