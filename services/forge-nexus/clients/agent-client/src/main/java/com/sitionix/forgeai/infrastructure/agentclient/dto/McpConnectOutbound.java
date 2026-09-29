package com.sitionix.forgeai.infrastructure.agentclient.dto;
import java.net.URI;
public record McpConnectOutbound(String displayName,URI endpoint,String browserBinding) {
    @Override public String toString(){return "McpConnectOutbound[redacted]";}
}
