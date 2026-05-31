package com.sitionix.forgeai.infrastructure.resources;

import com.sitionix.forgeai.domain.model.ticket.lane.AgentInstructions;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(InstructionResourcesProperties.class)
public class ResourceInstructionRepository implements InstructionRepository {

    private final InstructionResourcesProperties properties;
    private final ResourceLoader resourceLoader;

    private Map<String, AgentInstructions> agentInstructions;
    private Set<String> sharedInstructions;
    private Set<String> sharedInstructionRefs;

    @PostConstruct
    public void init() {
        this.agentInstructions = new LinkedHashMap<>();
        this.properties.getAgents().forEach((agentId, config) -> this.agentInstructions.put(
                agentId,
                AgentInstructions.builder()
                        .agentInstruction(this.readClasspathText(config.getInstructions()))
                        .endpoint(config.getEndpoint())
                        .additionalInstructions(this.readClasspathTexts(config.getAdditionalInstructions()))
                        .build()
        ));

        this.sharedInstructions = new LinkedHashSet<>();
        this.properties.getShared().forEach(path -> this.sharedInstructions.add(this.readClasspathText(path)));
        this.sharedInstructionRefs = new LinkedHashSet<>(this.properties.getShared());
    }

    @Override
    public AgentInstructions findInstructionsByAgentId(final String agentId) {
        final AgentInstructions instruction = this.agentInstructions.get(agentId);
        if (instruction == null) {
            throw new IllegalArgumentException("Agent instruction not found for agentId: " + agentId);
        }
        return AgentInstructions.builder()
                .agentInstruction(instruction.getAgentInstruction())
                .endpoint(instruction.getEndpoint())
                .additionalInstructions(new LinkedHashSet<>(instruction.getAdditionalInstructions()))
                .sharedInstructions(new LinkedHashSet<>(this.sharedInstructions))
                .build();
    }

    @Override
    public Set<String> findSharedInstructionRefs() {
        return new LinkedHashSet<>(this.sharedInstructionRefs);
    }

    private Set<String> readClasspathTexts(final Set<String> paths) {
        final Set<String> values = new LinkedHashSet<>();
        paths.forEach(path -> values.add(this.readClasspathText(path)));
        return values;
    }

    private String readClasspathText(final String classpathResourcePath) {
        final Resource resource = this.resourceLoader.getResource("classpath:" + classpathResourcePath);
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read instruction resource: " + classpathResourcePath, exception);
        }
    }
}
