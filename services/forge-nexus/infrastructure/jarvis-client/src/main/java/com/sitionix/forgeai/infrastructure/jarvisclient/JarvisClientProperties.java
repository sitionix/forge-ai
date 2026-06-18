package com.sitionix.forgeai.infrastructure.jarvisclient;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "forge.ai.infrastructure.jarvis")
public class JarvisClientProperties {

    private static final Set<String> ALLOWED_HOSTS = Set.of("127.0.0.1", "localhost");

    private boolean enabled = true;
    private URI baseUrl = URI.create("http://127.0.0.1:7071");
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(120);

    @PostConstruct
    public void validate() {
        if (!this.enabled) {
            return;
        }
        this.validateBaseUrl();
    }

    public void validateBaseUrl() {
        if (this.baseUrl == null) {
            throw new IllegalStateException("Jarvis base URL is required");
        }
        final String scheme = this.baseUrl.getScheme();
        if (!"http".equalsIgnoreCase(scheme)) {
            throw new IllegalStateException("Jarvis base URL must use http");
        }
        final String host = this.baseUrl.getHost();
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase())) {
            throw new IllegalStateException("Jarvis base URL must point to localhost");
        }
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public URI getBaseUrl() {
        return this.baseUrl;
    }

    public void setBaseUrl(final URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getConnectTimeout() {
        return this.connectTimeout;
    }

    public void setConnectTimeout(final Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return this.readTimeout;
    }

    public void setReadTimeout(final Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
