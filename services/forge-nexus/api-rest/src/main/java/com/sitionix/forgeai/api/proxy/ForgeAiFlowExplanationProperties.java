package com.sitionix.forgeai.api.proxy;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "forge.ai.query.flow-explanation")
public class ForgeAiFlowExplanationProperties {

    private int requestTimeoutSeconds = 180;

    @PostConstruct
    public void validate() {
        if (this.requestTimeoutSeconds <= 0) {
            throw new IllegalStateException("Flow explanation request timeout must be positive");
        }
    }

    public int getRequestTimeoutSeconds() {
        return this.requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(final int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public Duration requestTimeout() {
        if (this.requestTimeoutSeconds <= 0) {
            throw new IllegalStateException("Flow explanation request timeout must be positive");
        }
        return Duration.ofSeconds(this.requestTimeoutSeconds);
    }
}
