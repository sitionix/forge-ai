package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.ScopeContext;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.model.ticket.lane.AgentInstructions;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LaneStepPromptBuilderTest {

    private final LaneStepPromptBuilder laneStepPromptBuilder = new LaneStepPromptBuilder(
            () -> List.of("shared/common-rules.md"),
            new FakeInstructionRepository(),
            new ObjectMapper()
    );

    @Test
    void buildStartPrompt_includesMetadataTasksScopeAndResolvedCommonInstructions() {
        final String prompt = this.laneStepPromptBuilder.buildStartPrompt(this.lane(), this.strategy(), this.input());

        assertThat(prompt).contains("START_PROMPT");
        assertThat(prompt).contains("ticketId:");
        assertThat(prompt).contains("ticketKey:");
        assertThat(prompt).contains("laneId:");
        assertThat(prompt).contains("agentId:");
        assertThat(prompt).contains("scope:");
        assertThat(prompt).contains("Task payloads:");
        assertThat(prompt).contains("Scope context:");
        assertThat(prompt).contains("### shared/common-rules.md");
        assertThat(prompt).contains("resolved::shared/common-rules.md");
        assertThat(prompt).contains("Return exactly one JSON object");
    }

    @Test
    void buildStepPrompt_usesYamlStepAndResolvedInstructionText() {
        final String prompt = this.laneStepPromptBuilder.buildStepPrompt(this.lane(), this.strategy(), this.strategy().getSteps().getFirst(), this.input(), 1, 3);

        assertThat(prompt).contains("STEP_PROMPT");
        assertThat(prompt).contains("stepIndex: 1");
        assertThat(prompt).contains("stepId: scope_slicing");
        assertThat(prompt).contains("### lane-instructions/analyzer/scope-slicing.md");
        assertThat(prompt).contains("resolved::lane-instructions/analyzer/scope-slicing.md");
        assertThat(prompt).doesNotContain("architect-handoff.md");
        assertThat(prompt).doesNotContain("qa-lead-handoff.md");
    }

    @Test
    void buildCorrectionPrompt_forFinalStep_includesCompletionContract() {
        final LaneStrategyStep completionStep = this.strategy().getSteps().getLast();
        final String prompt = this.laneStepPromptBuilder.buildCorrectionPrompt(this.lane(), completionStep, "summary must be non-empty", true);

        assertThat(prompt).contains("CORRECTION_PROMPT");
        assertThat(prompt).contains("Active step id: completion");
        assertThat(prompt).contains("Validation error: summary must be non-empty");
        assertThat(prompt).contains("completionPayload");
    }

    private ReadyToStartLane lane() {
        return ReadyToStartLane.builder()
                .ticketId(UUID.randomUUID())
                .ticketKey("SITIONIX-1")
                .laneId(UUID.randomUUID())
                .agent(Agent.ANALYZER)
                .scope("backendforfrontendservice-sox")
                .serviceId("bffssox")
                .sourceTerminalTty("/dev/ttys001")
                .attempt(1)
                .build();
    }

    private LaneStrategy strategy() {
        return LaneStrategy.builder()
                .agentId("analyzer")
                .version(1)
                .sessionMode("single_session")
                .steps(List.of(
                        LaneStrategyStep.builder().id("scope_slicing").title("Scope Slicing").order(1).instructionRefs(List.of("lane-instructions/analyzer/scope-slicing.md")).build(),
                        LaneStrategyStep.builder().id("architect_handoff").title("Architect Handoff").order(2).instructionRefs(List.of("lane-instructions/analyzer/architect-handoff.md")).build(),
                        LaneStrategyStep.builder().id("completion").title("Completion").order(3).instructionRefs(List.of("lane-instructions/analyzer/completion-content.md")).build()
                ))
                .build();
    }

    private AgentExecutionInput<AgentTicketPayload> input() {
        final ApiPayload task = ApiPayload.builder()
                .scope("backendforfrontendservice-sox")
                .summary("Implement analyzer task payload support.")
                .build();
        return AgentExecutionInput.<AgentTicketPayload>builder()
                .tasks(new LinkedHashSet<>(Set.of(task)))
                .scope(ScopeContext.builder().scope("backendforfrontendservice-sox").build())
                .build();
    }

    private static final class FakeInstructionRepository implements InstructionRepository {

        @Override
        public AgentInstructions findInstructionsByAgentId(final String agentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String findInstructionTextByRef(final String instructionRef) {
            return "resolved::" + instructionRef;
        }

        @Override
        public Set<String> findSharedInstructionRefs() {
            return Set.of("shared/common-rules.md");
        }
    }
}
