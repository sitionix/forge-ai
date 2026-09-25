package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.McpRuntimeGrant;
import java.util.UUID;

/** Protocol view preparation; SDK definitions remain in infrastructure. */
public interface McpRuntimeToolView {
    void prepare(McpRuntimeGrant grant, byte[] credential);
    void remove(UUID grantId);
    void revokeConnection(UUID connectionId);
    void revokeExecution(UUID turnId);
}
