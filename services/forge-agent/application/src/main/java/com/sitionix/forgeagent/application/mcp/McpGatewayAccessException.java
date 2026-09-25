package com.sitionix.forgeagent.application.mcp;

/** A fixed, public-safe denial; neither a bearer nor upstream detail is retained. */
public final class McpGatewayAccessException extends RuntimeException {
    public McpGatewayAccessException() { super("MCP runtime access denied"); }
}
