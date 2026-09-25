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
    private static final String CREDENTIAL_PURPOSE = "credential";
    private final McpConnectionRepository connections;
    private final ForgeInstanceIdentityRepository identity;
    private final McpCredentialCipher cipher;
    private final McpRemoteProbe remote;
    private final McpToolInventoryRepository inventory;

    public McpProbeService(McpConnectionRepository connections, ForgeInstanceIdentityRepository identity,
                           McpCredentialCipher cipher, McpRemoteProbe remote, McpToolInventoryRepository inventory) {
        this.connections = Objects.requireNonNull(connections);
        this.identity = Objects.requireNonNull(identity);
        this.cipher = Objects.requireNonNull(cipher);
        this.remote = Objects.requireNonNull(remote);
        this.inventory = Objects.requireNonNull(inventory);
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
            credential = cipher.decrypt(installation, id, CREDENTIAL_PURPOSE, encrypted);
        }
        try {
            McpProbeReport report = remote.probe(connection.endpoint(), connection.authType(), credential);
            inventory.replace(installation, id, connection.endpoint(), connection.authType(), encrypted, report.tools());
            return report;
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
        return inventory.approve(identity.getOrCreate(), id, tools);
    }
}
