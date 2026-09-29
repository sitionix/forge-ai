package com.sitionix.forgeagent.domain.model;
import java.util.Objects;
public record McpOAuthClientRegistration(McpOAuthConfiguration configuration,McpOAuthCredentials credentials) {
    public McpOAuthClientRegistration{Objects.requireNonNull(configuration);Objects.requireNonNull(credentials);}
    @Override public String toString(){return "McpOAuthClientRegistration[redacted]";}
}
