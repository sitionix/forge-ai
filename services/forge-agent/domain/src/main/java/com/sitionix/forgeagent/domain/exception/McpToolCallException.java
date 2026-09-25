package com.sitionix.forgeagent.domain.exception;

/** Safe internal outcome distinct from a tool result with isError=true. */
public final class McpToolCallException extends RuntimeException {
    public enum Kind { SCHEMA_CHANGED, PROTOCOL_FAILURE }

    private final Kind kind;
    private final Integer protocolCode;

    public McpToolCallException(Kind kind, Integer protocolCode) {
        super("MCP tool call failed: " + kind);
        this.kind = kind;
        this.protocolCode = protocolCode;
    }

    public Kind kind() { return kind; }
    public Integer protocolCode() { return protocolCode; }
}
