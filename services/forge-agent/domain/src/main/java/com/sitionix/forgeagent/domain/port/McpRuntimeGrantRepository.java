package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.McpRuntimeGrant;
import com.sitionix.forgeagent.domain.model.McpRuntimeGrantHandle;
import java.util.Optional;
import java.util.UUID;

public interface McpRuntimeGrantRepository {
    McpRuntimeGrantHandle issue(McpRuntimeGrant grant);
    Optional<McpRuntimeGrant> resolve(String token, UUID connectionId);
    boolean admit(String token, UUID connectionId);
    void revokeConnection(UUID connectionId);
    void revokeExecution(UUID turnId);
    void remove(UUID grantId);
}
