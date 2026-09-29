package com.sitionix.forgeai.api.mcp;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("forge.mcp.oauth")
public record McpOAuthBrowserProperties(@DefaultValue("http://127.0.0.1:9099") URI browserOrigin,
        @DefaultValue("10m") Duration transactionTtl) {
    public McpOAuthBrowserProperties {
        if(browserOrigin==null || browserOrigin.getHost()==null || browserOrigin.getUserInfo()!=null
                || browserOrigin.getQuery()!=null || browserOrigin.getFragment()!=null || !browserOrigin.getPath().isEmpty()
                || !("http".equalsIgnoreCase(browserOrigin.getScheme()) || "https".equalsIgnoreCase(browserOrigin.getScheme()))
                || transactionTtl==null || transactionTtl.isZero() || transactionTtl.isNegative())
            throw new IllegalArgumentException("Invalid OAuth browser configuration");
    }
}
