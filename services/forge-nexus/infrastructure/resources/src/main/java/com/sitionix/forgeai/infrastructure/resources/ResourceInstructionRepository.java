package com.sitionix.forgeai.infrastructure.resources;

import com.sitionix.forgeai.domain.repository.InstructionRepository;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
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

    private Set<String> sharedInstructionRefs;

    @PostConstruct
    public void init() {
        this.sharedInstructionRefs = new LinkedHashSet<>(this.properties.getShared());
    }

    @Override
    public String findInstructionTextByRef(final String instructionRef) {
        return this.readClasspathText(this.normalizeInstructionRef(instructionRef));
    }

    @Override
    public Set<String> findSharedInstructionRefs() {
        return new LinkedHashSet<>(this.sharedInstructionRefs);
    }

    private String readClasspathText(final String classpathResourcePath) {
        final Resource resource = this.resourceLoader.getResource("classpath:" + classpathResourcePath);
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read instruction resource: " + classpathResourcePath, exception);
        }
    }

    private String normalizeInstructionRef(final String instructionRef) {
        if (instructionRef == null || instructionRef.isBlank()) {
            throw new IllegalArgumentException("Instruction ref must not be blank");
        }
        return instructionRef.startsWith("instructions/")
                ? instructionRef
                : "instructions/" + instructionRef;
    }
}
