package com.sitionix.forgeai.infrastructure.resources;

import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "forge.ai.instructions")
public class InstructionResourcesProperties {

    private Set<String> shared = new LinkedHashSet<>();
}
