package com.sitionix.forgeagent.domain.exception;

/** Safe application boundary. Provider exception descriptions and causes never cross it. */
public final class McpOAuthException extends RuntimeException {
    private final int status;
    private final String code;
    private McpOAuthException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
    public static McpOAuthException unavailable() { return new McpOAuthException(503, "MCP_OAUTH_UNAVAILABLE", "OAuth provider is unavailable."); }
    public static McpOAuthException invalidResponse() { return new McpOAuthException(502, "MCP_OAUTH_INVALID_RESPONSE", "OAuth provider returned an invalid response."); }
    public static McpOAuthException reconnect() { return new McpOAuthException(409, "MCP_OAUTH_RECONNECT_REQUIRED", "OAuth authorization requires reconnect."); }
    public static McpOAuthException invalidTransaction() { return new McpOAuthException(400, "MCP_OAUTH_INVALID_TRANSACTION", "OAuth authorization is invalid or expired. Connect again."); }
    public static McpOAuthException denied() { return new McpOAuthException(403, "MCP_OAUTH_DENIED", "OAuth authorization was declined."); }
    public static McpOAuthException endpointDenied() { return new McpOAuthException(400, "MCP_ENDPOINT_DENIED", "OAuth endpoint is not allowed."); }
}
