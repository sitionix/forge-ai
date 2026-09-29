package com.sitionix.forgeagent.application.mcp;

import com.sitionix.forgeagent.domain.exception.McpProbeException;
import com.sitionix.forgeagent.domain.model.McpAuthType;
import com.sitionix.forgeagent.domain.model.McpProbeReport;
import com.sitionix.forgeagent.domain.model.McpEncryptedCredential;
import com.sitionix.forgeagent.domain.model.McpConnection;
import com.sitionix.forgeagent.domain.model.McpAllowedTool;
import com.sitionix.forgeagent.domain.model.McpToolSummary;
import com.sitionix.forgeagent.domain.port.*;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Management-only probe. Never invokes a discovered tool. */
public final class McpProbeService {
    private final McpConnectionRepository connections;
    private final ForgeInstanceIdentityRepository identity;
    private final McpCredentialService credentials;
    private final McpRemoteProbe remote;
    private final McpToolInventoryRepository inventory;
    private final McpGatewayService gateway;

    public McpProbeService(McpConnectionRepository connections, ForgeInstanceIdentityRepository identity,
                           McpCredentialService credentials, McpRemoteProbe remote, McpToolInventoryRepository inventory,
                           McpGatewayService gateway) {
        this.connections=Objects.requireNonNull(connections);this.identity=Objects.requireNonNull(identity);
        this.credentials=Objects.requireNonNull(credentials);this.remote=Objects.requireNonNull(remote);
        this.inventory=Objects.requireNonNull(inventory);this.gateway=gateway;
    }

    public McpProbeReport test(UUID id) {
        UUID installation = identity.getOrCreate();
        var connection = connections.findById(installation, id)
                .orElseThrow(() -> new NoSuchElementException("MCP connection not found"));
        byte[] credential = null;
        McpEncryptedCredential encrypted = null;
        if (connection.authType() != McpAuthType.NONE) {
            encrypted = connections.credential(installation, id)
                    .orElseThrow(() -> new McpProbeException(McpProbeException.Reason.AUTH_REQUIRED));
            credential = credentials.resolve(connection);
        }
        try {
            McpProbeReport report = remote.probe(connection.endpoint(), connection.authType().protocolType(), credential);
            inventory.replace(installation, id, connection.endpoint(), connection.authType(), encrypted, report.tools(), connection.oauthAuthorizationId());
            if (gateway != null && !connections.findById(installation, id)
                    .map(after -> after.allowedTools().equals(connection.allowedTools())).orElse(false))
                gateway.revokeConnection(id);
            return report;
        } catch (McpProbeException failure) {
            if (connection.authType()==McpAuthType.OAUTH && (failure.reason()==McpProbeException.Reason.AUTH_REQUIRED || failure.reason()==McpProbeException.Reason.FORBIDDEN)) {
                credentials.authorizationFailed(connection);throw com.sitionix.forgeagent.domain.exception.McpOAuthException.reconnect();
            }
            throw failure;
        } finally {
            if (credential != null) Arrays.fill(credential, (byte) 0);
        }
    }

    public List<McpToolSummary> inventory(UUID id) {
        UUID installation = identity.getOrCreate();
        connections.findById(installation, id).orElseThrow(() -> new NoSuchElementException("MCP connection not found"));
        return inventory.list(installation, id);
    }

    public McpConnection approve(UUID id, Set<McpAllowedTool> tools) {
        if (tools == null) throw new IllegalArgumentException("Invalid MCP tool approval");
        UUID installation = identity.getOrCreate();
        var before = connections.findById(installation, id)
                .orElseThrow(() -> new NoSuchElementException("MCP connection not found"));
        var approved = inventory.approve(installation, id, tools);
        if (gateway != null && !approved.allowedTools().equals(before.allowedTools())) gateway.revokeConnection(id);
        return approved;
    }
}
