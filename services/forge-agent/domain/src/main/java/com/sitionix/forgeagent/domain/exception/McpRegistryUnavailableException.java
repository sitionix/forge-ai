package com.sitionix.forgeagent.domain.exception;

public final class McpRegistryUnavailableException extends RuntimeException {
    public McpRegistryUnavailableException(Throwable cause) {
        super("MCP Registry is unavailable", cause);
    }

    public McpRegistryUnavailableException() {
        super("MCP Registry is unavailable");
    }
}
