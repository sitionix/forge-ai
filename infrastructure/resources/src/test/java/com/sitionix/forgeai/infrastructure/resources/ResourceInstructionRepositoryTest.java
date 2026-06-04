package com.sitionix.forgeai.infrastructure.resources;

import com.sitionix.forgeai.domain.model.ticket.lane.AgentInstructions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceInstructionRepositoryTest {

    private ResourceInstructionRepository resourceInstructionRepository;

    @BeforeEach
    void setUp() {
        final InstructionResourcesProperties properties = new InstructionResourcesProperties();
        final InstructionResourcesProperties.AgentConfig analyzerConfig = new InstructionResourcesProperties.AgentConfig();
        analyzerConfig.setInstructions("instructions/agents/analyzer.md");
        analyzerConfig.setAdditionalInstructions(new LinkedHashSet<>(Set.of("instructions/additional-instructions/scope-context-usage.md")));

        final InstructionResourcesProperties.AgentConfig qaLeadConfig = new InstructionResourcesProperties.AgentConfig();
        qaLeadConfig.setInstructions("instructions/agents/qa_lead.md");
        qaLeadConfig.setAdditionalInstructions(new LinkedHashSet<>());

        final InstructionResourcesProperties.AgentConfig implementBeConfig = new InstructionResourcesProperties.AgentConfig();
        implementBeConfig.setInstructions("instructions/agents/implement_be.md");
        implementBeConfig.setAdditionalInstructions(new LinkedHashSet<>(Set.of(
                "instructions/additional-instructions/java-style-basics.md"
        )));

        final InstructionResourcesProperties.AgentConfig testUnitConfig = new InstructionResourcesProperties.AgentConfig();
        testUnitConfig.setInstructions("instructions/agents/test_unit.md");
        testUnitConfig.setAdditionalInstructions(new LinkedHashSet<>(Set.of(
                "instructions/additional-instructions/pr-workflow.md"
        )));

        final InstructionResourcesProperties.AgentConfig implementFeConfig = new InstructionResourcesProperties.AgentConfig();
        implementFeConfig.setInstructions("instructions/agents/implement_fe.md");
        implementFeConfig.setAdditionalInstructions(new LinkedHashSet<>(Set.of(
                "instructions/additional-instructions/preparation-to-work.md",
                "instructions/additional-instructions/pr-workflow.md"
        )));

        final InstructionResourcesProperties.AgentConfig reviewerConfig = new InstructionResourcesProperties.AgentConfig();
        reviewerConfig.setInstructions("instructions/agents/reviewer.md");
        reviewerConfig.setAdditionalInstructions(new LinkedHashSet<>(Set.of(
                "instructions/additional-instructions/java-style-basics.md"
        )));

        final LinkedHashMap<String, InstructionResourcesProperties.AgentConfig> agents = new LinkedHashMap<>();
        agents.put("analyzer", analyzerConfig);
        agents.put("qa_lead", qaLeadConfig);
        agents.put("implement_be", implementBeConfig);
        agents.put("implement_fe", implementFeConfig);
        agents.put("test_unit", testUnitConfig);
        agents.put("reviewer", reviewerConfig);
        properties.setAgents(agents);
        properties.setShared(new LinkedHashSet<>(Set.of("instructions/shared/common-rules.md")));

        this.resourceInstructionRepository = new ResourceInstructionRepository(properties, new DefaultResourceLoader());
        this.resourceInstructionRepository.init();
    }

    @Test
    void givenAnalyzerAgent_whenFindInstructionsByAgentId_thenReturnResolvedInstructions() {
        //when
        final AgentInstructions actual = this.resourceInstructionRepository.findInstructionsByAgentId("analyzer");

        //then
        assertThat(actual.getAgentInstruction()).contains("# Analyzer Instructions");
        assertThat(actual.getAdditionalInstructions()).hasSize(1);
        assertThat(actual.getAdditionalInstructions().iterator().next()).contains("# Scope Context Usage");
        assertThat(actual.getSharedInstructions()).hasSize(1);
        assertThat(actual.getSharedInstructions().iterator().next()).contains("# Common Agent Rules");
    }

    @Test
    void givenInstructionRef_whenFindInstructionTextByRef_thenReturnResolvedText() {
        final String actual = this.resourceInstructionRepository.findInstructionTextByRef("lane-instructions/analyzer/scope-slicing.md");

        assertThat(actual).contains("# Analyzer Scope Slicing");
        assertThat(actual).doesNotContain("{{TASKS}}");
    }

    @Test
    void givenUnknownAgent_whenFindInstructionsByAgentId_thenThrowIllegalArgumentException() {
        //when then
        assertThatThrownBy(() -> this.resourceInstructionRepository.findInstructionsByAgentId("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Agent instruction not found for agentId: unknown");
    }

    @Test
    void givenImplementBeAgent_whenFindInstructionsByAgentId_thenReturnResolvedInstructions() {
        //when
        final AgentInstructions actual = this.resourceInstructionRepository.findInstructionsByAgentId("implement_be");

        //then
        assertThat(actual.getAgentInstruction()).contains("# Implement BE Instructions");
        assertThat(actual.getAdditionalInstructions()).hasSize(1);
        assertThat(actual.getAdditionalInstructions()).anySatisfy(value -> assertThat(value).contains("# Java Style Basics"));
        assertThat(actual.getSharedInstructions()).hasSize(1);
        assertThat(actual.getSharedInstructions().iterator().next()).contains("# Common Agent Rules");
    }

    @Test
    void givenQaLeadAgent_whenFindInstructionsByAgentId_thenReturnResolvedInstructions() {
        //when
        final AgentInstructions actual = this.resourceInstructionRepository.findInstructionsByAgentId("qa_lead");

        //then
        assertThat(actual.getAgentInstruction()).contains("# QA Lead Instructions");
        assertThat(actual.getAdditionalInstructions()).isEmpty();
        assertThat(actual.getSharedInstructions()).hasSize(1);
        assertThat(actual.getSharedInstructions().iterator().next()).contains("# Common Agent Rules");
    }

    @Test
    void givenImplementFeAgent_whenFindInstructionsByAgentId_thenReturnResolvedInstructions() {
        //when
        final AgentInstructions actual = this.resourceInstructionRepository.findInstructionsByAgentId("implement_fe");

        //then
        assertThat(actual.getAgentInstruction()).contains("# Implement FE Instructions");
        assertThat(actual.getAdditionalInstructions()).hasSize(2);
        assertThat(actual.getAdditionalInstructions()).anySatisfy(value -> assertThat(value).contains("# Preparation To Work"));
        assertThat(actual.getAdditionalInstructions()).anySatisfy(value -> assertThat(value).contains("# PR Workflow"));
        assertThat(actual.getSharedInstructions()).hasSize(1);
        assertThat(actual.getSharedInstructions().iterator().next()).contains("# Common Agent Rules");
    }

    @Test
    void givenTestUnitAgent_whenFindInstructionsByAgentId_thenReturnResolvedInstructions() {
        //when
        final AgentInstructions actual = this.resourceInstructionRepository.findInstructionsByAgentId("test_unit");

        //then
        assertThat(actual.getAgentInstruction()).contains("# Test Unit Instructions");
        assertThat(actual.getAdditionalInstructions()).hasSize(1);
        assertThat(actual.getAdditionalInstructions().iterator().next()).contains("# PR Workflow");
        assertThat(actual.getSharedInstructions()).hasSize(1);
        assertThat(actual.getSharedInstructions().iterator().next()).contains("# Common Agent Rules");
    }

    @Test
    void givenReviewerAgent_whenFindInstructionsByAgentId_thenReturnResolvedInstructions() {
        //when
        final AgentInstructions actual = this.resourceInstructionRepository.findInstructionsByAgentId("reviewer");

        //then
        assertThat(actual.getAgentInstruction()).contains("# Reviewer Instructions");
        assertThat(actual.getAdditionalInstructions()).hasSize(1);
        assertThat(actual.getAdditionalInstructions().iterator().next()).contains("# Java Style Basics");
        assertThat(actual.getSharedInstructions()).hasSize(1);
        assertThat(actual.getSharedInstructions().iterator().next()).contains("# Common Agent Rules");
    }
}
