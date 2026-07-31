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

    public void validate() {
        if (this.enabled == null) {
            throw new IllegalStateException("forge.ai.infrastructure.knowledge.enabled is required");
        }
        if (this.baseUrl == null) {
            throw new IllegalStateException("forge.ai.infrastructure.knowledge.base-url is required");
        }
        this.validateBaseUrl();
        this.validateTimeout(this.connectTimeout, "connect-timeout");
        this.validateTimeout(this.readTimeout, "read-timeout");
    }

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

    private void validateBaseUrl() {
        if (!"http".equals(this.baseUrl.getScheme())) {
            throw new IllegalStateException("forge.ai.infrastructure.knowledge.base-url must use http");
        }
        final String host = this.baseUrl.getHost();
        if (!"localhost".equals(host) && !"127.0.0.1".equals(host) && !"::1".equals(host) && !"[::1]".equals(host)) {
            throw new IllegalStateException("forge.ai.infrastructure.knowledge.base-url must target localhost");
        }
    }

    private void validateTimeout(final Duration timeout, final String propertyName) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("forge.ai.infrastructure.knowledge." + propertyName + " must be positive");
        }
        Math.toIntExact(timeout.toMillis());
    }
}
