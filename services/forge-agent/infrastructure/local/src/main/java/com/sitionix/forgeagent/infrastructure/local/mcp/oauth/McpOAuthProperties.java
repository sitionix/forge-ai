package com.sitionix.forgeagent.infrastructure.local.mcp.oauth;

import com.sitionix.forgeagent.domain.model.McpOAuthConfiguration;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("forge.mcp.oauth")
public record McpOAuthProperties(@DefaultValue("2s") Duration connectTimeout,
                                  @DefaultValue("5s") Duration readTimeout,
                                  @DefaultValue("10m") Duration transactionTtl,
                                  @DefaultValue("http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/agents/integrations/mcp/oauth/callback") URI callbackUri,
                                  @DefaultValue("") String sslBundle) {
    public McpOAuthProperties {
        positive(connectTimeout); positive(readTimeout); positive(transactionTtl);
        McpOAuthConfiguration.validateUri(callbackUri);
    }
    private static void positive(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("OAuth duration must be positive");
    }
}
