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
        analyzerConfig.setEndpoint("/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/analyzer/complete");
        analyzerConfig.setAdditionalInstructions(new LinkedHashSet<>(Set.of("instructions/shared/completion-callback.md")));

        final InstructionResourcesProperties.AgentConfig implementBeConfig = new InstructionResourcesProperties.AgentConfig();
        implementBeConfig.setInstructions("instructions/agents/implement_be.md");
        implementBeConfig.setEndpoint("/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/implement-be/complete");
        implementBeConfig.setAdditionalInstructions(new LinkedHashSet<>(Set.of(
                "instructions/additional-instructions/java-style-basics.md"
        )));

        final LinkedHashMap<String, InstructionResourcesProperties.AgentConfig> agents = new LinkedHashMap<>();
        agents.put("analyzer", analyzerConfig);
        agents.put("implement_be", implementBeConfig);
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
        assertThat(actual.getEndpoint()).isEqualTo("/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/analyzer/complete");
        assertThat(actual.getAdditionalInstructions()).hasSize(1);
        assertThat(actual.getAdditionalInstructions().iterator().next()).contains("# Completion Callback Rules");
        assertThat(actual.getSharedInstructions()).hasSize(1);
        assertThat(actual.getSharedInstructions().iterator().next()).contains("# Common Agent Rules");
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
        assertThat(actual.getEndpoint()).isEqualTo("/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/implement-be/complete");
        assertThat(actual.getAdditionalInstructions()).hasSize(1);
        assertThat(actual.getAdditionalInstructions()).anySatisfy(value -> assertThat(value).contains("# Java Style Basics"));
        assertThat(actual.getSharedInstructions()).hasSize(1);
        assertThat(actual.getSharedInstructions().iterator().next()).contains("# Common Agent Rules");
    }
}
