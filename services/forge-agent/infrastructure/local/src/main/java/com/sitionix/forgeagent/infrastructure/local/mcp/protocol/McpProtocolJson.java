package com.sitionix.forgeagent.infrastructure.local.mcp.protocol;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared, lossless numeric decoding for opaque MCP schemas, arguments and results. */
public final class McpProtocolJson {
    private McpProtocolJson() { }

    public static ObjectMapper copy(ObjectMapper source) {
        return source.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
}
