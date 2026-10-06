package com.sitionix.forgeai.domain.model.mcp;
import java.net.URI;
public record McpConnectCommand(String displayName,URI endpoint) {
    @Override public String toString(){return "McpConnectCommand[redacted]";}
}
