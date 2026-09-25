package com.sitionix.forgeagent.domain.exception;

public final class McpProbeException extends RuntimeException {
    public enum Reason { AUTH_REQUIRED, FORBIDDEN, UNSUPPORTED_PROTOCOL, INVALID_RESPONSE, UNAVAILABLE, ENDPOINT_DENIED }
    private final Reason reason;
    public McpProbeException(Reason reason) {
        super("MCP probe failed: " + reason);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
