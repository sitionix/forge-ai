package com.sitionix.forgeai.application.laneexecution;

import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "forge.ai.supervised-execution")
public class SupervisedExecutionProperties {

    private boolean enabled;
    private Set<String> agents = new LinkedHashSet<>();
    private int correctionAttempts = 2;

    public boolean isSupervisedAgent(final String agentId) {
        return this.enabled && this.agents.contains(agentId);
    }
}
