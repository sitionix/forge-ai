package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.net.URI;
import java.util.UUID;
public record McpOAuthStartInbound(UUID transactionId,UUID connectionId,URI authorizationUrl) {
    @Override public String toString(){return "McpOAuthStartInbound[redacted]";}
}
