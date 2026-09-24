package com.sitionix.forgeai.domain.exception;

/** Only parsed error fields cross the MCP domain port. Raw transport data stays in the client. */
public final class McpAgentClientException extends RuntimeException {
    private final int statusCode;
    private final String code;
    private final String upstreamMessage;
    private final String correlationId;

    public McpAgentClientException(int statusCode, String code, String upstreamMessage,
                                   String correlationId) {
        super("MCP Agent management failure", null, false, false);
        this.statusCode = statusCode;
        this.code = code;
        this.upstreamMessage = upstreamMessage;
        this.correlationId = correlationId;
    }

    public int statusCode() { return statusCode; }
    public String code() { return code; }
    public String upstreamMessage() { return upstreamMessage; }
    public String correlationId() { return correlationId; }
}
