package com.sitionix.forgeagent.application.mcp;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The trusted execution and live connection policy owner for runtime MCP grants. */
public class McpGatewayService implements McpGatewayRuntime {
    private static final String CREDENTIAL_PURPOSE = "credential";
    private static final Logger log = LoggerFactory.getLogger(McpGatewayService.class);
    private final AgentExecutionSessionRepository sessions;
    private final NodeRunRepository nodes;
    private final WorkflowRunRepository workflows;
    private final ProjectRepository projects;
    private final McpConnectionRepository connections;
    private final ForgeInstanceIdentityRepository identity;
    private final McpRuntimeGrantRepository grants;
    private final McpRuntimeToolView views;
    private final McpCredentialCipher cipher;
    private final McpRemoteToolClient remote;
    private final Clock clock;

    public McpGatewayService(AgentExecutionSessionRepository sessions, NodeRunRepository nodes,
                             WorkflowRunRepository workflows, ProjectRepository projects,
                             McpConnectionRepository connections, ForgeInstanceIdentityRepository identity,
                             McpRuntimeGrantRepository grants, McpRuntimeToolView views,
                             McpCredentialCipher cipher, McpRemoteToolClient remote, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions);
        this.nodes = Objects.requireNonNull(nodes);
        this.workflows = Objects.requireNonNull(workflows);
        this.projects = Objects.requireNonNull(projects);
        this.connections = Objects.requireNonNull(connections);
        this.identity = Objects.requireNonNull(identity);
        this.grants = Objects.requireNonNull(grants);
        this.views = Objects.requireNonNull(views);
        this.cipher = Objects.requireNonNull(cipher);
        this.remote = Objects.requireNonNull(remote);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Callable only from an Agent-owned execution path, never from HTTP management. */
    public McpRuntimeGrantHandle issue(AgentSessionExecutionClaim claim, Instant executionDeadline, UUID connectionId) {
        if (claim == null || executionDeadline == null || connectionId == null) throw denied();
        var session = liveSession(claim.sessionId(), claim.turnId(), claim.nodeRunId(),
                claim.leaseOwnerId(), claim.leaseToken());
        if (session == null || !sessions.lockCurrentLease(claim.sessionId(), claim.leaseOwnerId(), claim.leaseToken()))
            throw denied();
        var workflow = workflows.findById(session.workflowRunId()).orElseThrow(McpGatewayService::denied);
        if (workflow.status() != WorkflowRunStatus.RUNNING || projects.findById(workflow.projectId()).isEmpty())
            throw denied();
        UUID installation = identity.getOrCreate();
        var connection = connections.findById(installation, connectionId).orElseThrow(McpGatewayService::denied);
        if (!connection.enabled() || !connection.projectAccess().allows(workflow.projectId())
                || connection.allowedTools().isEmpty()) throw denied();
        var encrypted = credential(installation, connection);
        Instant deadline = executionDeadline;
        if (!deadline.isAfter(clock.instant())) throw denied();
        var grant = new McpRuntimeGrant(UUID.randomUUID(), installation, claim.sessionId(), claim.turnId(),
                claim.nodeRunId(), session.workflowRunId(), workflow.projectId(), claim.leaseOwnerId(),
                claim.leaseToken(), connectionId, connection.endpoint(), connection.authType(),
                credentialIdentity(encrypted), connection.allowedTools(), deadline);
        McpRuntimeGrantHandle handle = grants.issue(grant);
        byte[] plaintext = null;
        try {
            plaintext = decrypt(grant, encrypted);
            views.prepare(grant, plaintext);
            validate(grant);
            return handle;
        } catch (RuntimeException failure) {
            grants.remove(grant.id());
            views.remove(grant.id());
            throw failure;
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Used before every SDK initialize/list and again immediately before call. */
    @Override public McpRuntimeGrant authorize(String token, UUID connectionId) {
        var grant = grants.resolve(token, connectionId).orElseThrow(McpGatewayService::denied);
        try {
            validate(grant);
            return grant;
        } catch (McpGatewayAccessException denial) {
            grants.remove(grant.id());
            views.remove(grant.id());
            throw denial;
        }
    }

    @Override public McpToolCallResult call(String token, UUID connectionId, String toolName,
                                  String fingerprint, String argumentsJson) {
        var grant = authorize(token, connectionId);
        if (toolName == null || fingerprint == null
                || !grant.tools().contains(new McpAllowedTool(toolName, fingerprint))) throw denied();
        var connection = connections.findById(grant.installationId(), connectionId)
                .orElseThrow(McpGatewayService::denied);
        var encrypted = credential(grant.installationId(), connection);
        if (!connection.endpoint().equals(grant.endpoint()) || connection.authType() != grant.authType()
                || !credentialIdentity(encrypted).equals(grant.credentialIdentity())) throw denied();
        byte[] plaintext = decrypt(grant, encrypted);
        long started = System.nanoTime();
        try {
            var result = remote.call(grant.endpoint(), grant.authType(), plaintext,
                    toolName, fingerprint, argumentsJson, () -> {
                        try {
                            authorize(token, connectionId);
                            return grants.admit(token, connectionId);
                        } catch (McpGatewayAccessException denial) {
                            return false;
                        }
                    });
            telemetry(grant, toolName, started, result.isError() ? "TOOL_ERROR" : "OK");
            return result;
        } catch (RuntimeException failure) {
            telemetry(grant, toolName, started, "FAILED");
            throw failure;
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    public void revokeConnection(UUID connectionId) {
        grants.revokeConnection(connectionId);
        views.revokeConnection(connectionId);
    }

    public void revokeExecution(UUID turnId) {
        grants.revokeExecution(turnId);
        views.revokeExecution(turnId);
    }

    private void validate(McpRuntimeGrant grant) {
        if (liveSession(grant.sessionId(), grant.turnId(), grant.nodeRunId(),
                grant.leaseOwnerId(), grant.leaseToken()) == null) throw denied();
        var workflow = workflows.findById(grant.workflowRunId()).orElseThrow(McpGatewayService::denied);
        if (workflow.status() != WorkflowRunStatus.RUNNING || !grant.projectId().equals(workflow.projectId())
                || projects.findById(grant.projectId()).isEmpty()) throw denied();
        var connection = connections.findById(grant.installationId(), grant.connectionId())
                .orElseThrow(McpGatewayService::denied);
        if (!connection.enabled() || !connection.projectAccess().allows(grant.projectId())
                || !connection.endpoint().equals(grant.endpoint()) || connection.authType() != grant.authType()
                || !connection.allowedTools().containsAll(grant.tools())
                || !credentialIdentity(credential(grant.installationId(), connection)).equals(grant.credentialIdentity()))
            throw denied();
    }

    private AgentExecutionSession liveSession(UUID sessionId, UUID turnId, UUID nodeRunId,
                                               String owner, long token) {
        var session = sessions.findSession(sessionId).orElse(null);
        if (session == null || session.status() != AgentExecutionSessionStatus.ACTIVE
                || !nodeRunId.equals(session.activeNodeRunId())
                || !owner.equals(session.leaseOwnerId()) || token != session.leaseToken()
                || session.leaseExpiresAt() == null || !session.leaseExpiresAt().isAfter(clock.instant())) return null;
        var node = nodes.findById(nodeRunId).orElse(null);
        if (node == null || node.status() != NodeRunStatus.RUNNING
                || !session.workflowRunId().equals(node.workflowRunId())) return null;
        var allocation = sessions.findByNodeRunId(nodeRunId).orElse(null);
        if (allocation == null || allocation.turn() == null
                || !turnId.equals(allocation.turn().id())
                || allocation.turn().status() != AgentExecutionTurnStatus.ACTIVE
                || allocation.session() == null || !sessionId.equals(allocation.session().id())) return null;
        return session;
    }

    private McpEncryptedCredential credential(UUID installation, McpConnection connection) {
        if (connection.authType() == McpAuthType.NONE) {
            if (connection.credentialConfigured()) throw denied();
            return null;
        }
        if (!connection.credentialConfigured()) throw denied();
        return connections.credential(installation, connection.id()).orElseThrow(McpGatewayService::denied);
    }

    private byte[] decrypt(McpRuntimeGrant grant, McpEncryptedCredential encrypted) {
        return encrypted == null ? null : cipher.decrypt(grant.installationId(), grant.connectionId(),
                CREDENTIAL_PURPOSE, encrypted);
    }

    private static String credentialIdentity(McpEncryptedCredential encrypted) {
        if (encrypted == null) return "none";
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(encrypted.keyId().getBytes(StandardCharsets.UTF_8));
            byte[] bytes = encrypted.bytes();
            try { digest.update(bytes); } finally { Arrays.fill(bytes, (byte) 0); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static McpGatewayAccessException denied() { return new McpGatewayAccessException(); }

    private static void telemetry(McpRuntimeGrant grant, String toolName, long started, String outcome) {
        log.info("MCP runtime call connection={} turn={} toolId={} durationMs={} outcome={}",
                grant.connectionId(), grant.turnId(), toolId(toolName),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), outcome);
    }

    private static String toolId(String name) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(name.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
