package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "forge.ai.infrastructure.knowledge")
public class KnowledgeActiveProfileClientProperties {

    private Boolean enabled;
    private URI baseUrl;
    private Duration connectTimeout;
    private Duration readTimeout;

    public boolean enabled() {
        return Boolean.TRUE.equals(this.enabled);
    }

    public URI baseUrl() {
        return this.baseUrl;
    }

    public Duration connectTimeout() {
        return this.connectTimeout;
    }

    public Duration readTimeout() {
        return this.readTimeout;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public void setBaseUrl(final URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setConnectTimeout(final Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public void setReadTimeout(final Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
