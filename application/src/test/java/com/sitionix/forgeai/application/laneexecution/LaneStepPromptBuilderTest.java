package com.sitionix.forgeai.application.laneexecution;

import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyPromptConfig;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LaneStepPromptBuilderTest {

    private LaneStepPromptBuilder laneStepPromptBuilder;

    @BeforeEach
    void setUp() {
        final LaneStrategyPromptConfig promptConfig = () -> List.of("shared/common-rules.md", "additional-instructions/completion-callback.md");
        this.laneStepPromptBuilder = new LaneStepPromptBuilder(promptConfig);
    }

    @Test
    void givenLaneAndStrategy_whenBuildStartPrompt_thenUseOnlyMetadataAndRuntimeRefs() {
        final String prompt = this.laneStepPromptBuilder.buildStartPrompt(
                this.readyToStartLane(),
                this.strategy(),
                Path.of(".forge-ai/runtime/SITIONIX-1/lane-id/execution-id/start-context.json")
        );

        assertThat(prompt).startsWith("START_PROMPT");
        assertThat(prompt.length()).isLessThan(1500);
        assertThat(prompt)
                .contains("ticketId:")
                .contains("ticketKey:")
                .contains("laneId:")
                .contains("agentId:")
                .contains("scope:")
                .contains("strategyId:")
                .contains("strategyVersion:")
                .contains("workspaceRoot:")
                .contains("startContext:")
                .contains(".forge-ai/runtime/SITIONIX-1/lane-id/execution-id/start-context.json")
                .contains("commonInstructionRefs:")
                .contains("- shared/common-rules.md")
                .contains("- additional-instructions/completion-callback.md");
        assertThat(prompt)
                .doesNotContain("# Common Agent Rules")
                .doesNotContain("agentInstruction:")
                .doesNotContain("additionalInstructions:")
                .doesNotContain("sharedInstructions:")
                .doesNotContain("Lazy Instruction Strategy")
                .doesNotContain("architect-handoff.md")
                .doesNotContain("qa-lead-handoff.md")
                .doesNotContain("completion-callback.md\n#")
                .doesNotContain("taskPayloads:")
                .doesNotContain("{{TASK}}")
                .doesNotContain("{{TASKS}}");
    }

    @Test
    void givenStep_whenBuildStepPrompt_thenUseOnlyActiveRuntimeFile() {
        final String prompt = this.laneStepPromptBuilder.buildStepPrompt(
                this.step("architect_handoff", "Architect Handoff", 2, null, List.of("lane-instructions/analyzer/architect-handoff.md")),
                2,
                4,
                Path.of(".forge-ai/runtime/SITIONIX-1/lane-id/execution-id/steps/2-architect_handoff.md")
        );

        assertThat(prompt).startsWith("STEP_PROMPT");
        assertThat(prompt.length()).isLessThan(1500);
        assertThat(prompt)
                .contains("stepIndex:")
                .contains("2/4")
                .contains("stepId:")
                .contains("architect_handoff")
                .contains("stepTitle:")
                .contains("Architect Handoff")
                .contains("runtimeStepFile:")
                .contains(".forge-ai/runtime/SITIONIX-1/lane-id/execution-id/steps/2-architect_handoff.md");
        assertThat(prompt)
                .doesNotContain("ticketId:")
                .doesNotContain("ticketKey:")
                .doesNotContain("laneId:")
                .doesNotContain("agentId:")
                .doesNotContain("scope:")
                .doesNotContain("commonInstructionRefs:")
                .doesNotContain("architect-handoff.md\n#")
                .doesNotContain("taskPayloads:")
                .doesNotContain("Lazy Instruction Strategy");
    }

    @Test
    void givenStep_whenBuildCorrectionPrompt_thenUseOnlyActiveRuntimeFile() {
        final String prompt = this.laneStepPromptBuilder.buildCorrectionPrompt(
                "scope_slicing",
                Path.of(".forge-ai/runtime/SITIONIX-1/lane-id/execution-id/steps/1-scope_slicing.md")
        );

        assertThat(prompt).startsWith("CORRECTION_PROMPT");
        assertThat(prompt.length()).isLessThan(1500);
        assertThat(prompt)
                .contains("stepId=scope_slicing")
                .contains(".forge-ai/runtime/SITIONIX-1/lane-id/execution-id/steps/1-scope_slicing.md");
        assertThat(prompt)
                .doesNotContain("ticketId:")
                .doesNotContain("laneId:")
                .doesNotContain("commonInstructionRefs:")
                .doesNotContain("Lazy Instruction Strategy");
    }

    @Test
    void givenConfiguredCommonRefs_whenQueryCommonInstructionRefs_thenReturnYamlBackedRefs() {
        assertThat(this.laneStepPromptBuilder.commonInstructionRefs())
                .containsExactly("shared/common-rules.md", "additional-instructions/completion-callback.md");
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
}
