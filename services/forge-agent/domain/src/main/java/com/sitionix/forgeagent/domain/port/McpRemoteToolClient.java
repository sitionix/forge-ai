package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.McpAuthType;
import com.sitionix.forgeagent.domain.model.McpToolCallResult;
import java.net.URI;
import java.util.function.BooleanSupplier;

/** Internal upstream tool-call boundary; never a management route. */
public interface McpRemoteToolClient {
    default McpToolCallResult call(URI endpoint, McpAuthType authType, byte[] credential,
                                  String toolName, String expectedSchemaFingerprint, String argumentsJson) {
        return call(endpoint, authType, credential, toolName, expectedSchemaFingerprint,
                argumentsJson, () -> true);
    }

    McpToolCallResult call(URI endpoint, McpAuthType authType, byte[] credential,
                           String toolName, String expectedSchemaFingerprint, String argumentsJson,
                           BooleanSupplier admitBeforeDispatch);
}
