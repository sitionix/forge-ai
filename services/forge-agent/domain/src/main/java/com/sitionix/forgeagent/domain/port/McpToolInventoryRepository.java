package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.*;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface McpToolInventoryRepository {
    List<McpToolSummary> list(UUID installationId, UUID connectionId);
    /** Replace only if the probed endpoint and auth type are still current. */
    void replace(UUID installationId, UUID connectionId, URI endpoint, McpAuthType authType,
                 McpEncryptedCredential credential, List<McpToolSummary> tools);
    /** Approvals must exactly match current discovered name and schema fingerprint. */
    McpConnection approve(UUID installationId, UUID connectionId, Set<McpAllowedTool> tools);
}
