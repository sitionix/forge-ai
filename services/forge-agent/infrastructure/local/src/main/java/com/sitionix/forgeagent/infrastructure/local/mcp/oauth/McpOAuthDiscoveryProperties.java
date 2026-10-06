package com.sitionix.forgeagent.infrastructure.local.mcp.oauth;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("forge.mcp.oauth")
public record McpOAuthDiscoveryProperties(@DefaultValue("20s") Duration discoveryTimeout) {
    private static final Duration MANAGEMENT_PREPARATION_LIMIT=Duration.ofSeconds(25);
    public McpOAuthDiscoveryProperties {
        if(discoveryTimeout==null || discoveryTimeout.isNegative() || discoveryTimeout.isZero()
                || discoveryTimeout.compareTo(MANAGEMENT_PREPARATION_LIMIT)>0)
            throw new IllegalArgumentException("OAuth preparation must fit the Nexus management HTTP budget");
    }
}
