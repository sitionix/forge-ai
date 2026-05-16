package com.sitionix.forgeai.infrastructure.resources;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "forge.ai.instructions")
public class InstructionResourcesProperties {

    private Map<String, AgentConfig> agents = new LinkedHashMap<>();

    private Set<String> shared = new LinkedHashSet<>();

    @Getter
    @Setter
    public static class AgentConfig {
        private String instructions;
        private String endpoint;
        private Set<String> additionalInstructions = new LinkedHashSet<>();
    }
}
