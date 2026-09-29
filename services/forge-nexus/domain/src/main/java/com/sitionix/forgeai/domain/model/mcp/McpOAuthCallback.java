package com.sitionix.forgeai.domain.model.mcp;

public record McpOAuthCallback(String state,String browserBinding,String code,String error,String issuer) {
    @Override public String toString() { return "McpOAuthCallback[redacted]"; }
}
