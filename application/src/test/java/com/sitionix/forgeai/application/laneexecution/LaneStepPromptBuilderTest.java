package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.ScopeContext;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LaneStepPromptBuilderTest {

    @Mock
    private InstructionRepository instructionRepository;

    private LaneStepPromptBuilder laneStepPromptBuilder;

    @BeforeEach
    void setUp() {
        this.laneStepPromptBuilder = new LaneStepPromptBuilder(new ObjectMapper(), this.instructionRepository);
        when(this.instructionRepository.findInstructionTextByRef(anyString())).thenAnswer(invocation -> {
            final String ref = invocation.getArgument(0);
            return "Instruction for " + ref + "\n{{TASKS}}";
        });
    }

    @Test
    void givenApiTaskPayloads_whenBuildStepPrompt_thenInjectSerializedTasks() {
        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(UUID.randomUUID())
                .ticketKey("SITIONIX-1")
                .laneId(UUID.randomUUID())
                .agent(Agent.API)
                .scope("backendforfrontendservice-sox")
                .serviceId("bffssox")
                .sourceTerminalTty("/dev/ttys001")
                .attempt(1)
                .build();

        final LaneStrategyStep step = LaneStrategyStep.builder()
                .id("pr")
                .title("Pull Request")
                .order(4)
                .instructionRefs(List.of("additional-instructions/pr-workflow.md"))
                .build();

        final ApiPayload apiPayload = ApiPayload.builder()
                .scope("backendforfrontendservice-sox")
                .summary("Add authenticated flow and palette endpoints.")
                .build();

        final AgentExecutionInput<ApiPayload> input = AgentExecutionInput.<ApiPayload>builder()
                .tasks(new LinkedHashSet<>(Set.of(apiPayload)))
                .scope(ScopeContext.builder().scope("backendforfrontendservice-sox").build())
                .agentInstruction("Agent instruction with {{TASKS}}")
                .additionalInstructions(Set.of("Additional instruction with {{TASK}}"))
                .sharedInstructions(Set.of("Shared instruction with {{tasks}}"))
                .build();

        final String startPrompt = this.laneStepPromptBuilder.startPrompt(lane, (AgentExecutionInput) input, 4);
        final String stepPrompt = this.laneStepPromptBuilder.stepPrompt(lane, step, 4, 6, (AgentExecutionInput) input);

        assertThat(startPrompt)
                .contains("Supervised lane session started.")
                .contains("\"scope\":\"backendforfrontendservice-sox\"")
                .contains("Additional instruction with")
                .contains("Shared instruction with");
        assertThat(stepPrompt)
                .contains("Task payloads for this lane:")
                .contains("\"scope\":\"backendforfrontendservice-sox\"")
                .contains("\"summary\":\"Add authenticated flow and palette endpoints.\"")
                .contains("Instruction for additional-instructions/pr-workflow.md");
    }
}
