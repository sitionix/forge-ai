package com.sitionix.forgeagent.domain.model;

/** Internal persistence aggregate. Never returned by management reads. */
public record McpConnectionState(McpConnection connection, McpEncryptedCredential credential) {
    public McpConnectionState {
        if (connection == null || connection.credentialConfigured() != (credential != null))
            throw new IllegalArgumentException("Invalid credential state");
    }
    @Override public String toString() { return "McpConnectionState[redacted]"; }
}
