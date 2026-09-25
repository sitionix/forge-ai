package com.sitionix.forgeagent.domain.model;

/** Opaque protocol content stays serialized until the gateway returns it to its MCP caller. */
public record McpToolCallResult(boolean isError, String contentJson, String structuredContentJson) {
    public McpToolCallResult {
        if (contentJson == null || contentJson.isBlank())
            throw new IllegalArgumentException("Invalid MCP tool result");
    }
}
