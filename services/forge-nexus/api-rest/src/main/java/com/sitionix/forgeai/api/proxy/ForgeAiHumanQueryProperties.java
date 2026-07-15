package com.sitionix.forgeai.api.proxy;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "forge.ai.query.human-query")
public class ForgeAiHumanQueryProperties {

    private int requestTimeoutSeconds = 180;
    private Duration requestTimeout;

    @PostConstruct
    public void validate() {
        if (this.requestTimeoutSeconds <= 0 || this.requestTimeout().compareTo(Duration.ZERO) <= 0) {
            throw new IllegalStateException("Human query request timeout must be positive");
        }
    }

    public int getRequestTimeoutSeconds() {
        return this.requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(final int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public Duration getRequestTimeout() {
        return this.requestTimeout;
    }

    public void setRequestTimeout(final Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration requestTimeout() {
        final Duration timeout = this.requestTimeout == null ? Duration.ofSeconds(this.requestTimeoutSeconds) : this.requestTimeout;
        if (timeout.compareTo(Duration.ZERO) <= 0) {
            throw new IllegalStateException("Human query request timeout must be positive");
        }
        return timeout;
    }
}
