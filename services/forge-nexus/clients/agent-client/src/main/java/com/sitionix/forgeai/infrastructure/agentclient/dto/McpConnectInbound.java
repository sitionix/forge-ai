package com.sitionix.forgeai.infrastructure.agentclient.dto;
public record McpConnectInbound(McpConnectionInboundResponse connection,McpOAuthStartInbound authorization) {
    @Override public String toString(){return "McpConnectInbound[redacted]";}
}
