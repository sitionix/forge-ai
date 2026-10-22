package com.sitionix.forgeagent.application.mcp;

import com.sitionix.forgeagent.application.runtime.NodeExecutionClaim;
import com.sitionix.forgeagent.domain.exception.McpOAuthException;
import com.sitionix.forgeagent.domain.exception.McpProbeException;
import com.sitionix.forgeagent.domain.exception.McpToolCallException;
import com.sitionix.forgeagent.domain.model.AgentSessionExecutionClaim;
import com.sitionix.forgeagent.domain.model.McpConnection;
import com.sitionix.forgeagent.domain.model.McpExecutionPreparation;
import com.sitionix.forgeagent.domain.model.McpExecutionSelection;
import com.sitionix.forgeagent.domain.model.McpRuntimeLaunchGrants;
import com.sitionix.forgeagent.domain.port.ForgeInstanceIdentityRepository;
import com.sitionix.forgeagent.domain.port.McpConnectionRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Objects;

/** Selects approved global connections for one trusted execution. */
public final class McpExecutionSelectionService {
    private final WorkflowRunRepository workflows;
    private final McpConnectionRepository connections;
    private final ForgeInstanceIdentityRepository identity;
    private final McpGatewayService gateway;

    public McpExecutionSelectionService(WorkflowRunRepository workflows, McpConnectionRepository connections,
                                        ForgeInstanceIdentityRepository identity, McpGatewayService gateway) {
        this.workflows = Objects.requireNonNull(workflows);
        this.connections = Objects.requireNonNull(connections);
        this.identity = Objects.requireNonNull(identity);
        this.gateway = Objects.requireNonNull(gateway);
    }

    public McpExecutionPreparation prepare(NodeExecutionClaim claim, Instant deadline) {
        if (claim == null || deadline == null) throw new IllegalArgumentException("Invalid MCP execution");
        var workflow = workflows.findById(claim.workflowRunId())
                .orElseThrow(() -> new IllegalStateException("MCP execution workflow is unavailable"));
        return prepareProject(workflow.projectId(),claim.agentSessionClaim(),null,deadline);
    }
    public McpExecutionPreparation prepareStandalone(com.sitionix.forgeagent.domain.model.standalone.StandaloneExecutionClaim claim,Instant deadline) {
        return prepareProject(claim.session().projectId(),null,claim,deadline);
    }
    private McpExecutionPreparation prepareProject(java.util.UUID projectId,AgentSessionExecutionClaim session,
            com.sitionix.forgeagent.domain.model.standalone.StandaloneExecutionClaim standalone,Instant deadline) {
        var eligible = connections.findAll(identity.getOrCreate()).stream()
                .filter(connection -> connection.enabled() && connection.projectAccess().allows(projectId) && !connection.allowedTools().isEmpty())
                .sorted(Comparator.comparing(McpConnection::id)).toList();
        if(session==null && standalone==null && !eligible.isEmpty()) throw new IllegalStateException("MCP execution requires a tracked session");
        var entries = new ArrayList<McpExecutionSelection.Entry>();
        var diagnostics = new ArrayList<McpExecutionSelection.Diagnostic>();
        var tokens = new HashMap<String, String>();
        try {
            for (var connection : eligible) {
                String alias = "forge_" + connection.id().toString().replace("-", "");
                try {
                    var grant = standalone==null?gateway.issue(session, deadline, connection.id()):gateway.issueStandalone(standalone,deadline,connection.id());
                    entries.add(new McpExecutionSelection.Entry(alias, connection.id(),
                            connection.displayName(), connection.allowedTools()));
                    tokens.put(alias, grant.token());
                } catch (McpProbeException | McpToolCallException | McpGatewayAccessException | McpOAuthException unavailable) {
                    diagnostics.add(new McpExecutionSelection.Diagnostic(connection.id(),
                            McpExecutionSelection.DiagnosticCode.CONNECTION_UNAVAILABLE));
                }
            }
        } catch (RuntimeException failure) {
            if (session != null || standalone != null) {
                try { gateway.revokeExecution(standalone==null?session.turnId():standalone.turnId()); }
                catch (RuntimeException cleanupFailure) { /* No bearer escaped this preparation boundary. */ }
            }
            throw new IllegalStateException("MCP execution preparation failed");
        }
        return new McpExecutionPreparation(new McpExecutionSelection(entries, diagnostics),
                new McpRuntimeLaunchGrants(tokens));
    }

    public void activateStandalone(com.sitionix.forgeagent.domain.model.standalone.StandaloneExecutionClaim claim) { gateway.activateStandalone(claim); }
    public void revokeExecution(java.util.UUID turnId) { gateway.revokeExecution(turnId); }

    public void activateForDispatch(AgentSessionExecutionClaim claim) {
        if (claim == null) throw new IllegalArgumentException("Tracked MCP execution is required");
        gateway.activateForDispatch(claim);
    }

    public void revoke(AgentSessionExecutionClaim claim) {
        if (claim != null) gateway.revokeExecution(claim.turnId());
    }
}
