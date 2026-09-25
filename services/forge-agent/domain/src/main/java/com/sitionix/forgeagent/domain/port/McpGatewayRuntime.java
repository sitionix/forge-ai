package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.McpRuntimeGrant;
import com.sitionix.forgeagent.domain.model.McpToolCallResult;
import java.util.UUID;

/** Runtime-facing operations; issuing grants remains a trusted Agent application call. */
public interface McpGatewayRuntime {
    McpRuntimeGrant authorize(String token, UUID connectionId);
    McpToolCallResult call(String token, UUID connectionId, String toolName,
                           String fingerprint, String argumentsJson);
}
