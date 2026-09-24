package com.sitionix.forgeai.domain.exception;

/** MCP management failures contain only a fixed category, never an upstream payload or cause. */
public final class McpAgentClientException extends RuntimeException {
    public enum Category { INVALID_REQUEST, NOT_FOUND, UPSTREAM_UNAVAILABLE, UPSTREAM_ERROR }
    private final Category category;
    public McpAgentClientException(Category category) {
        super("MCP Agent management: " + category.name(), null, false, false);
        this.category=category;
    }
    public Category category(){return category;}
}
