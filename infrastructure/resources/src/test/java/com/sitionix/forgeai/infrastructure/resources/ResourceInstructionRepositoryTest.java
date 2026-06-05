package com.sitionix.forgeai.infrastructure.resources;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceInstructionRepositoryTest {

    private ResourceInstructionRepository resourceInstructionRepository;

    @BeforeEach
    void setUp() {
        final InstructionResourcesProperties properties = new InstructionResourcesProperties();
        properties.setShared(new LinkedHashSet<>(Set.of("instructions/shared/common-rules.md")));

        this.resourceInstructionRepository = new ResourceInstructionRepository(properties, new DefaultResourceLoader());
        this.resourceInstructionRepository.init();
    }

    @Test
    void givenInstructionRef_whenFindInstructionTextByRef_thenReturnResolvedText() {
        final String actual = this.resourceInstructionRepository.findInstructionTextByRef("lane-instructions/analyzer/scope-slicing.md");

        assertThat(actual).contains("# Analyzer Scope Slicing");
        assertThat(actual).doesNotContain("{{TASKS}}");
    }

    @Test
    void givenInstructionRefWithInstructionsPrefix_whenFindInstructionTextByRef_thenReturnResolvedText() {
        final String actual = this.resourceInstructionRepository.findInstructionTextByRef("instructions/shared/common-rules.md");

        assertThat(actual).contains("# Common Agent Rules");
    }

    @Test
    void givenSharedInstructionsConfigured_whenFindSharedInstructionRefs_thenReturnRefs() {
        assertThat(this.resourceInstructionRepository.findSharedInstructionRefs())
                .containsExactly("instructions/shared/common-rules.md");
    }

    @Test
    void givenUnknownInstructionRef_whenFindInstructionTextByRef_thenThrowIllegalStateException() {
        assertThatThrownBy(() -> this.resourceInstructionRepository.findInstructionTextByRef("missing.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to read instruction resource: instructions/missing.md");
    }
}
