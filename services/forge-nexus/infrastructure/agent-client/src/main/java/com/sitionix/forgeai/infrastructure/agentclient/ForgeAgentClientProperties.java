package com.sitionix.forgeai.infrastructure.agentclient;

import java.net.URI;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "forge.ai.infrastructure.agent")
@Getter
@Setter
public class ForgeAgentClientProperties {

    private Boolean enabled = true;

    private URI baseUrl;

    private Duration connectTimeout;

    private Duration readTimeout;

    public boolean enabled() {
        return Boolean.TRUE.equals(this.enabled);
    }
}
