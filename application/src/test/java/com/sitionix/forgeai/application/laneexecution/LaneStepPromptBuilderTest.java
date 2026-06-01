package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LaneStepPromptBuilderTest {

    private LaneStepPromptBuilder laneStepPromptBuilder;

    @BeforeEach
    void setUp() {
        this.laneStepPromptBuilder = new LaneStepPromptBuilder(new ObjectMapper());
    }

    @Test
    void givenApiTaskPayloads_whenBuildStepPrompt_thenInjectSerializedTasks() {
        final LaneStrategyStep step = mock(LaneStrategyStep.class);
        when(step.getId()).thenReturn("pr");
        when(step.getTitle()).thenReturn("Pull Request");
        when(step.getInstructionRefs()).thenReturn(java.util.List.of("additional-instructions/pr-workflow.md"));

        final ApiPayload apiPayload = ApiPayload.builder()
                .scope("backendforfrontendservice-sox")
                .summary("Add authenticated flow and palette endpoints.")
                .build();

        final String prompt = this.laneStepPromptBuilder.stepPrompt(step, 4, 6, Set.of(apiPayload));

        assertThat(prompt)
                .contains("Task payloads for this lane:")
                .contains("\"scope\":\"backendforfrontendservice-sox\"")
                .contains("\"summary\":\"Add authenticated flow and palette endpoints.\"");
    }
}
