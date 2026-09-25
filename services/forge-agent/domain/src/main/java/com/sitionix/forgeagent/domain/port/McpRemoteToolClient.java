package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.McpAuthType;
import com.sitionix.forgeagent.domain.model.McpToolCallResult;
import java.net.URI;

/** Internal protocol boundary for the future execution gateway; never a management route. */
public interface McpRemoteToolClient {
    McpToolCallResult call(URI endpoint, McpAuthType authType, byte[] credential,
                           String toolName, String expectedSchemaFingerprint, String argumentsJson);
}
