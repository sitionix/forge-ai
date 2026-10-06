package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.sitionix.forgeai.domain.model.mcp.McpOAuthCallback;
public record McpOAuthCallbackOutbound(String state,String browserBinding,String code,String error,String issuer) {
    public static McpOAuthCallbackOutbound from(McpOAuthCallback c){return new McpOAuthCallbackOutbound(c.state(),c.browserBinding(),c.code(),c.error(),c.issuer());}
    @Override public String toString(){return "McpOAuthCallbackOutbound[redacted]";}
}
