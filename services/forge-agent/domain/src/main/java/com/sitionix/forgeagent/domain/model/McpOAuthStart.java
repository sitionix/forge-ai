package com.sitionix.forgeagent.domain.model;
import java.net.URI;
import java.util.UUID;

public record McpOAuthStart(UUID transactionId, UUID connectionId, URI authorizationUrl) {
    @Override public String toString() { return "McpOAuthStart[redacted]"; }
}
