package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
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
        if (!"http".equalsIgnoreCase(this.baseUrl.getScheme())) {
            throw new IllegalStateException("forge.ai.infrastructure.knowledge.base-url must use http");
        }
        if (this.baseUrl.getUserInfo() != null) {
            throw new IllegalStateException("forge.ai.infrastructure.knowledge.base-url must not contain user info");
        }
        this.validatePort();
        final String host = this.normalizedHost();
        if (!"localhost".equals(host) && !"127.0.0.1".equals(host) && !"::1".equals(host)) {
            throw new IllegalStateException("forge.ai.infrastructure.knowledge.base-url must target localhost");
        }
        final String path = this.baseUrl.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalStateException("forge.ai.infrastructure.knowledge.base-url path must be empty or /");
        }
        if (this.baseUrl.getRawQuery() != null) {
            throw new IllegalStateException("forge.ai.infrastructure.knowledge.base-url must not contain a query");
        }
        if (this.baseUrl.getRawFragment() != null) {
            throw new IllegalStateException("forge.ai.infrastructure.knowledge.base-url must not contain a fragment");
        }
    }

    private void validatePort() {
        final String authority = this.baseUrl.getRawAuthority();
        if (authority == null || authority.isBlank()) {
            throw new IllegalStateException("forge.ai.infrastructure.knowledge.base-url host is required");
        }
        final boolean explicitPort = this.hasExplicitPort(authority);
        final int port = this.baseUrl.getPort();
        if (explicitPort && (port < 1 || port > 65535)) {
            throw new IllegalStateException("forge.ai.infrastructure.knowledge.base-url port must be absent or between 1 and 65535");
        }
    }

    private boolean hasExplicitPort(final String authority) {
        final String hostPort = authority.substring(authority.lastIndexOf('@') + 1);
        if (hostPort.startsWith("[")) {
            final int bracket = hostPort.indexOf(']');
            return bracket >= 0 && hostPort.length() > bracket + 1 && hostPort.charAt(bracket + 1) == ':';
        }
        return hostPort.lastIndexOf(':') >= 0;
    }

    private void validateTimeout(final Duration timeout, final String propertyName) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("forge.ai.infrastructure.knowledge." + propertyName + " must be positive");
        }
    }

    private String normalizedHost() {
        final String host = this.baseUrl.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("forge.ai.infrastructure.knowledge.base-url host is required");
        }
        final String normalized = host.toLowerCase(Locale.ROOT);
        if ("[::1]".equals(normalized)) {
            return "::1";
        }
        return normalized;
    }
}
