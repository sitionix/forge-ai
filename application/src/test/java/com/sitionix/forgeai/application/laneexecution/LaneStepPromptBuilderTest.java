package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.ForgeAiContractApi;
import com.sitionix.forgeai.domain.model.codex.ScopeContext;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class LaneStepPromptBuilderTest {

    @Mock
    private InstructionRepository instructionRepository;

    private LaneStepPromptBuilder laneStepPromptBuilder;

    @BeforeEach
    void setUp() {
        this.laneStepPromptBuilder = new LaneStepPromptBuilder(new ObjectMapper(), this.instructionRepository);
        lenient().when(this.instructionRepository.findInstructionTextByRef(anyString())).thenAnswer(invocation -> {
            final String ref = invocation.getArgument(0);
            return switch (ref) {
                case "shared/common-rules.md" -> """
                        # Common Agent Rules
                        
                        When supervised execution is active, return a single LANE_STEP_DONE response for the current step.
                        Include type, stepId, summary, and evidence fields.
                        """;
                case "lane-instructions/analyzer/scope-slicing.md" -> "# Analyzer Scope Slicing\n\nScope slicing instructions.";
                case "lane-instructions/analyzer/architect-handoff.md" -> "# Analyzer Architect Handoff\n\nArchitect handoff instructions.";
                case "lane-instructions/analyzer/qa-lead-handoff.md" -> "# Analyzer QA Lead Handoff\n\nQA lead handoff instructions.";
                case "additional-instructions/completion-callback.md" -> "# Completion Callback Rules\n\nCompletion instructions.";
                default -> "Instruction for " + ref;
            };
        });
    }

    @Test
    void givenLaneAndStrategy_whenBuildStartPrompt_thenContainMetadataAndCommonProtocolOnly() {
        final String prompt = this.laneStepPromptBuilder.buildStartPrompt(this.readyToStartLane(), this.strategy(), this.input());

        assertThat(prompt).startsWith("START_PROMPT");
        assertThat(prompt)
                .contains("ticketId:")
                .contains("ticketKey:")
                .contains("laneId:")
                .contains("agentId:")
                .contains("scope:")
                .contains("strategyId:")
                .contains("strategyVersion:")
                .contains("workspaceRoot:")
                .contains("scopeContext:")
                .contains("contractApi:")
                .contains("commonRules:")
                .contains("# Common Agent Rules")
                .contains("single LANE_STEP_DONE response")
                .contains("type, stepId, summary, and evidence fields");
        assertThat(prompt).doesNotContain("<<<LANE_STEP_DONE_JSON>>>");
        assertThat(prompt).doesNotContain("<<<END_LANE_STEP_DONE_JSON>>>");
        assertThat(prompt).doesNotContain("agentInstruction:");
        assertThat(prompt).doesNotContain("additionalInstructions:");
        assertThat(prompt).doesNotContain("sharedInstructions:");
        assertThat(prompt).doesNotContain("Lazy Instruction Strategy");
        assertThat(prompt).doesNotContain("architect-handoff.md");
        assertThat(prompt).doesNotContain("qa-lead-handoff.md");
        assertThat(prompt).doesNotContain("completion-callback.md");
    }

    @Test
    void givenScopeSlicingStep_whenBuildStepPrompt_thenContainOnlyActiveStepContentAndTasks() {
        final String prompt = this.laneStepPromptBuilder.buildStepPrompt(
                this.step("scope_slicing", "Scope Slicing", 1, "TASKS", List.of("lane-instructions/analyzer/scope-slicing.md")),
                1,
                4,
                this.readyToStartLane(),
                this.strategy(),
                this.input()
        );

        assertThat(prompt).startsWith("STEP_PROMPT");
        assertThat(prompt)
                .contains("stepIndex:")
                .contains("1/4")
                .contains("stepId:")
                .contains("scope_slicing")
                .contains("stepTitle:")
                .contains("Scope Slicing")
                .contains("taskPayloads:")
                .contains("Implement analyzer task payload support.")
                .contains("activeInstructions:")
                .contains("# Analyzer Scope Slicing")
                .contains("Scope slicing instructions.")
                .contains("Return the common LANE_STEP_DONE result block for this step id.");
        assertThat(prompt).doesNotContain("ticketId:");
        assertThat(prompt).doesNotContain("ticketKey:");
        assertThat(prompt).doesNotContain("laneId:");
        assertThat(prompt).doesNotContain("agentId:");
        assertThat(prompt).doesNotContain("scopeContext:");
        assertThat(prompt).doesNotContain("commonRules:");
        assertThat(prompt).doesNotContain("architect-handoff.md");
        assertThat(prompt).doesNotContain("qa-lead-handoff.md");
        assertThat(prompt).doesNotContain("completion-callback.md");
        assertThat(prompt).doesNotContain("Lazy Instruction Strategy");
        assertThat(prompt).doesNotContain("instructionRefs:");
        assertThat(prompt).doesNotContain("instructionTexts:");
        assertThat(prompt).doesNotContain("<<<LANE_STEP_DONE_JSON>>>");
    }

    @Test
    void givenArchitectStep_whenBuildStepPrompt_thenContainOnlyArchitectContent() {
        final String prompt = this.laneStepPromptBuilder.buildStepPrompt(
                this.step("architect_handoff", "Architect Handoff", 2, null, List.of("lane-instructions/analyzer/architect-handoff.md")),
                2,
                4,
                this.readyToStartLane(),
                this.strategy(),
                this.input()
        );

        assertThat(prompt).startsWith("STEP_PROMPT");
        assertThat(prompt)
                .contains("stepId:")
                .contains("architect_handoff")
                .contains("stepTitle:")
                .contains("Architect Handoff")
                .contains("activeInstructions:")
                .contains("# Analyzer Architect Handoff")
                .contains("Architect handoff instructions.");
        assertThat(prompt).doesNotContain("scope_slicing");
        assertThat(prompt).doesNotContain("qa_lead_handoff");
        assertThat(prompt).doesNotContain("completion-callback.md");
        assertThat(prompt).doesNotContain("taskPayloads:");
        assertThat(prompt).doesNotContain("ticketId:");
        assertThat(prompt).doesNotContain("commonRules:");
    }

    @Test
    void givenQaLeadStep_whenBuildStepPrompt_thenContainOnlyQaLeadContent() {
        final String prompt = this.laneStepPromptBuilder.buildStepPrompt(
                this.step("qa_lead_handoff", "QA Lead Handoff", 3, null, List.of("lane-instructions/analyzer/qa-lead-handoff.md")),
                3,
                4,
                this.readyToStartLane(),
                this.strategy(),
                this.input()
        );

        assertThat(prompt).contains("qa_lead_handoff");
        assertThat(prompt).contains("# Analyzer QA Lead Handoff");
        assertThat(prompt).doesNotContain("scope_slicing");
        assertThat(prompt).doesNotContain("architect_handoff");
        assertThat(prompt).doesNotContain("completion-callback.md");
    }

    @Test
    void givenCompletionStep_whenBuildStepPrompt_thenContainOnlyCompletionContent() {
        final String prompt = this.laneStepPromptBuilder.buildStepPrompt(
                this.step("completion", "Completion", 4, null, List.of("additional-instructions/completion-callback.md")),
                4,
                4,
                this.readyToStartLane(),
                this.strategy(),
                this.input()
        );

        assertThat(prompt).contains("completion");
        assertThat(prompt).contains("# Completion Callback Rules");
        assertThat(prompt).doesNotContain("scope_slicing");
        assertThat(prompt).doesNotContain("architect_handoff");
        assertThat(prompt).doesNotContain("qa_lead_handoff");
        assertThat(prompt).doesNotContain("Lazy Instruction Strategy");
    }

    @Test
    void givenInvalidStep_whenBuildCorrectionPrompt_thenReturnDistinctCorrectionMessage() {
        final String correctionPrompt = this.laneStepPromptBuilder.buildCorrectionPrompt("scope_slicing");
        final String stepPrompt = this.laneStepPromptBuilder.buildStepPrompt(
                this.step("scope_slicing", "Scope Slicing", 1, "TASKS", List.of("lane-instructions/analyzer/scope-slicing.md")),
                1,
                4,
                this.readyToStartLane(),
                this.strategy(),
                this.input()
        );

        assertThat(correctionPrompt).startsWith("CORRECTION_PROMPT");
        assertThat(correctionPrompt)
                .contains("stepId=scope_slicing")
                .contains("Do not continue to another step");
        assertThat(correctionPrompt).isNotEqualTo(stepPrompt);
    }

    private ReadyToStartLane readyToStartLane() {
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
                        this.step("scope_slicing", "Scope Slicing", 1, "TASKS", List.of("lane-instructions/analyzer/scope-slicing.md")),
                        this.step("architect_handoff", "Architect Handoff", 2, null, List.of("lane-instructions/analyzer/architect-handoff.md")),
                        this.step("qa_lead_handoff", "QA Lead Handoff", 3, null, List.of("lane-instructions/analyzer/qa-lead-handoff.md")),
                        this.step("completion", "Completion", 4, null, List.of("additional-instructions/completion-callback.md"))
                ))
                .build();
    }

    private LaneStrategyStep step(final String id,
                                  final String title,
                                  final int order,
                                  final String taskPlaceholder,
                                  final List<String> instructionRefs) {
        return LaneStrategyStep.builder()
                .id(id)
                .title(title)
                .order(order)
                .taskPlaceholder(taskPlaceholder)
                .instructionRefs(instructionRefs)
                .build();
    }

    private AgentExecutionInput<AgentTicketPayload> input() {
        final ApiPayload task = ApiPayload.builder()
                .scope("backendforfrontendservice-sox")
                .summary("Implement analyzer task payload support.")
                .build();
        return AgentExecutionInput.<AgentTicketPayload>builder()
                .tasks(new LinkedHashSet<>(Set.of(task)))
                .contractApi(ForgeAiContractApi.builder()
                        .path("/api/v1/forge-ai")
                        .endpoint("/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/analyzer/complete")
                        .build())
                .scope(ScopeContext.builder().scope("backendforfrontendservice-sox").build())
                .build();
    }
}
