package com.sitionix.forgeai.application.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.laneexecution.LaneStepDoneResultParser;
import com.sitionix.forgeai.application.laneexecution.LaneStepPromptBuilder;
import com.sitionix.forgeai.application.laneexecution.support.FakeInteractiveCodexSessionRepository;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.ScopeContext;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.CodexSessionRepository;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import com.sitionix.forgeai.domain.repository.LaneExecutionRepository;
import com.sitionix.forgeai.domain.repository.LaneStrategyRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupervisedLaneExecutionUseCaseTest {

    @Mock
    private LaneStrategyRepository laneStrategyRepository;

    @Mock
    private InstructionRepository instructionRepository;

    private InMemoryLaneExecutionRepository laneExecutionRepository;
    private FakeInteractiveCodexSessionRepository codexSessionRepository;
    private SupervisedLaneExecutionUseCase useCase;

    @BeforeEach
    void setUp() {
        this.laneExecutionRepository = new InMemoryLaneExecutionRepository();
        this.stubInstructionRefs();
    }

    @Test
    void givenValidOutputs_whenExecute_thenSendStartAndStepPromptsInOrder() {
        final ReadyToStartLane lane = this.readyToStartLane();
        final LaneStrategy strategy = this.strategy();
        when(this.laneStrategyRepository.findByAgentId("analyzer")).thenReturn(strategy);
        this.useCase = this.buildUseCase(this.happyPathPlanner());

        this.useCase.execute(lane, this.inputWithTasks(), 1);

        final List<String> history = this.codexSessionRepository.history(this.codexSessionRepository.sessionIds().getFirst());
        final List<String> serviceMessages = history.stream()
                .filter(value -> value.startsWith("service:"))
                .toList();

        assertThat(serviceMessages).hasSize(4);
        assertThat(serviceMessages.getFirst())
                .startsWith("service:START_PROMPT")
                .contains("commonRules:")
                .contains("# Common Agent Rules")
                .contains("<<<LANE_STEP_DONE_JSON>>>")
                .contains("STEP_PROMPT")
                .contains("scope_slicing")
                .doesNotContain("Agent instruction.")
                .doesNotContain("Lazy Instruction Strategy")
                .doesNotContain("architect_handoff")
                .doesNotContain("qa_lead_handoff")
                .doesNotContain("completion-callback.md");

        assertThat(serviceMessages.get(1))
                .contains("STEP_PROMPT")
                .contains("architect_handoff")
                .doesNotContain("ticketId:")
                .doesNotContain("scopeContext:")
                .doesNotContain("scope_slicing")
                .doesNotContain("qa_lead_handoff")
                .doesNotContain("completion-callback.md");
        assertThat(serviceMessages.get(2))
                .contains("STEP_PROMPT")
                .contains("qa_lead_handoff")
                .doesNotContain("ticketId:")
                .doesNotContain("scope_slicing")
                .doesNotContain("architect_handoff")
                .doesNotContain("completion-callback.md");
        assertThat(serviceMessages.get(3))
                .contains("STEP_PROMPT")
                .contains("completion")
                .doesNotContain("ticketId:")
                .doesNotContain("scope_slicing")
                .doesNotContain("architect_handoff")
                .doesNotContain("qa_lead_handoff");

        assertThat(history).anyMatch(value -> value.equals("codex:" + this.validStepResult("scope_slicing")));
        assertThat(history).anyMatch(value -> value.equals("codex:" + this.validStepResult("architect_handoff")));
        assertThat(history).anyMatch(value -> value.equals("codex:" + this.validStepResult("qa_lead_handoff")));
        assertThat(history).anyMatch(value -> value.equals("codex:" + this.validStepResult("completion")));
        assertThat(history).noneMatch(value -> value.contains("CORRECTION_PROMPT"));

        assertThat(this.laneExecutionRepository.savedExecutions()).hasSize(1);
        assertThat(this.laneExecutionRepository.savedStepExecutions()).extracting(LaneStepExecution::getStepId)
                .containsExactly("scope_slicing", "architect_handoff", "qa_lead_handoff", "completion");
        assertThat(this.laneExecutionRepository.savedStepExecutions()).allMatch(LaneStepExecution::isDone);
    }

    @Test
    void givenInvalidThenValidOutput_whenExecute_thenSendCorrectionAndProceed() {
        final ReadyToStartLane lane = this.readyToStartLane();
        final LaneStrategy strategy = this.strategy();
        when(this.laneStrategyRepository.findByAgentId("analyzer")).thenReturn(strategy);
        this.useCase = this.buildUseCase(this.invalidThenValidPlanner());

        this.useCase.execute(lane, this.inputWithTasks(), 1);

        final List<String> history = this.codexSessionRepository.history(this.codexSessionRepository.sessionIds().getFirst());
        final List<String> serviceMessages = history.stream()
                .filter(value -> value.startsWith("service:"))
                .toList();
        assertThat(serviceMessages).hasSize(5);
        assertThat(serviceMessages.getFirst()).contains("START_PROMPT").contains("scope_slicing");
        assertThat(serviceMessages.get(1)).startsWith("service:CORRECTION_PROMPT");
        assertThat(serviceMessages.get(1)).contains("stepId=scope_slicing");
        assertThat(serviceMessages).anyMatch(value -> value.contains("architect_handoff"));
        assertThat(serviceMessages).anyMatch(value -> value.contains("qa_lead_handoff"));
        assertThat(serviceMessages).anyMatch(value -> value.contains("completion"));
        assertThat(history).anyMatch(value -> value.equals("codex:invalid-output"));
        assertThat(history).anyMatch(value -> value.equals("codex:" + this.validStepResult("scope_slicing")));
        assertThat(this.laneExecutionRepository.savedStepExecutions()).extracting(LaneStepExecution::getStepId)
                .containsExactly("scope_slicing", "architect_handoff", "qa_lead_handoff", "completion");
    }

    @Test
    void givenNoisyOutputWithValidMarker_whenExecute_thenParseAndProceed() {
        final ReadyToStartLane lane = this.readyToStartLane();
        final LaneStrategy strategy = this.strategy();
        when(this.laneStrategyRepository.findByAgentId("analyzer")).thenReturn(strategy);
        this.useCase = this.buildUseCase(this.noisyPlanner());

        this.useCase.execute(lane, this.inputWithTasks(), 1);

        assertThat(this.laneExecutionRepository.savedStepExecutions()).isNotEmpty();
        final List<String> history = this.codexSessionRepository.history(this.codexSessionRepository.sessionIds().getFirst());
        assertThat(history).anyMatch(value -> value.startsWith("service:STEP_PROMPT"));
        assertThat(history).anyMatch(value -> value.contains("architect_handoff"));
        assertThat(history).anyMatch(value -> value.contains("prose-before-marker"));
        assertThat(history).anyMatch(value -> value.equals("codex:" + this.noisyStepResult("architect_handoff")));
    }

    @Test
    void givenInvalidAfterCorrectionAttempts_whenExecute_thenStopWithoutPersistingStep() {
        final ReadyToStartLane lane = this.readyToStartLane();
        final LaneStrategy strategy = LaneStrategy.builder()
                .agentId("analyzer")
                .version(1)
                .sessionMode("single_session")
                .steps(List.of(
                        LaneStrategyStep.builder().id("scope_slicing").title("Scope Slicing").order(1).taskPlaceholder("TASKS").instructionRefs(List.of("lane-instructions/analyzer/scope-slicing.md")).build(),
                        LaneStrategyStep.builder().id("architect_handoff").title("Architect Handoff").order(2).instructionRefs(List.of("lane-instructions/analyzer/architect-handoff.md")).build()
                ))
                .build();
        when(this.laneStrategyRepository.findByAgentId("analyzer")).thenReturn(strategy);
        this.useCase = this.buildUseCase(this.alwaysInvalidPlanner());

        this.useCase.execute(lane, this.inputWithTasks(), 1);

        final List<String> history = this.codexSessionRepository.history(this.codexSessionRepository.sessionIds().getFirst());
        final List<String> serviceMessages = history.stream()
                .filter(value -> value.startsWith("service:"))
                .toList();
        assertThat(serviceMessages).hasSize(2);
        assertThat(serviceMessages.get(1)).startsWith("service:CORRECTION_PROMPT");
        assertThat(history).anyMatch(value -> value.startsWith("service:CORRECTION_PROMPT"));
        assertThat(this.laneExecutionRepository.savedStepExecutions()).isEmpty();
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
                        LaneStrategyStep.builder().id("scope_slicing").title("Scope Slicing").order(1).taskPlaceholder("TASKS").instructionRefs(List.of("lane-instructions/analyzer/scope-slicing.md")).build(),
                        LaneStrategyStep.builder().id("architect_handoff").title("Architect Handoff").order(2).instructionRefs(List.of("lane-instructions/analyzer/architect-handoff.md")).build(),
                        LaneStrategyStep.builder().id("qa_lead_handoff").title("QA Lead Handoff").order(3).instructionRefs(List.of("lane-instructions/analyzer/qa-lead-handoff.md")).build(),
                        LaneStrategyStep.builder().id("completion").title("Completion").order(4).instructionRefs(List.of("additional-instructions/completion-callback.md")).build()
                ))
                .build();
    }

    private AgentExecutionInput<AgentTicketPayload> inputWithTasks() {
        final ApiPayload task = ApiPayload.builder()
                .scope("backendforfrontendservice-sox")
                .summary("Implement analyzer task payload support.")
                .build();
        return AgentExecutionInput.<AgentTicketPayload>builder()
                .tasks(new LinkedHashSet<>(Set.of(task)))
                .scope(ScopeContext.builder().scope("backendforfrontendservice-sox").build())
                .build();
    }

    private void stubInstructionRefs() {
        when(this.instructionRepository.findInstructionTextByRef(anyString())).thenAnswer(invocation -> {
            final String ref = invocation.getArgument(0);
            return switch (ref) {
                case "shared/common-rules.md" -> """
                        # Common Agent Rules

                        <<<LANE_STEP_DONE_JSON>>>
                        {
                          "type": "LANE_STEP_DONE",
                          "stepId": "<activeStepId>",
                          "summary": "...",
                          "evidence": {}
                        }
                        <<<END_LANE_STEP_DONE_JSON>>>
                        """;
                case "lane-instructions/analyzer/scope-slicing.md" -> "# Analyzer Scope Slicing\n\nScope slicing instructions.";
                case "lane-instructions/analyzer/architect-handoff.md" -> "# Analyzer Architect Handoff\n\nArchitect handoff instructions.";
                case "lane-instructions/analyzer/qa-lead-handoff.md" -> "# Analyzer QA Lead Handoff\n\nQA lead handoff instructions.";
                case "additional-instructions/completion-callback.md" -> "# Completion Callback Rules\n\nCompletion instructions.";
                default -> "Instruction for " + ref;
            };
        });
    }

    private SupervisedLaneExecutionUseCase buildUseCase(final Function<String, List<String>> responsePlanner) {
        this.codexSessionRepository = new FakeInteractiveCodexSessionRepository(responsePlanner);
        final LaneStepPromptBuilder promptBuilder = new LaneStepPromptBuilder(new ObjectMapper(), this.instructionRepository);
        return new SupervisedLaneExecutionUseCase(
                this.laneStrategyRepository,
                this.laneExecutionRepository,
                this.codexSessionRepository,
                new LaneStepDoneResultParser(new ObjectMapper()),
                promptBuilder,
                new ObjectMapper()
        );
    }

    private String validStepResult(final String stepId) {
        return "<<<LANE_STEP_DONE_JSON>>>\n"
                + "{"
                + "\"type\":\"LANE_STEP_DONE\","
                + "\"stepId\":\"" + stepId + "\","
                + "\"summary\":\"done\","
                + "\"evidence\":{\"detail\":\"ok\"}"
                + "}\n"
                + "<<<END_LANE_STEP_DONE_JSON>>>";
    }

    private String invalidOutput() {
        return "invalid-output";
    }

    private String noisyStepResult(final String stepId) {
        return "prose-before-marker\n"
                + validStepResult(stepId)
                + "\nprose-after-marker";
    }

    private Function<String, List<String>> happyPathPlanner() {
        return message -> {
            if (message.contains("stepId:\nscope_slicing")) {
                return List.of(validStepResult("scope_slicing"));
            }
            if (message.contains("stepId:\narchitect_handoff")) {
                return List.of(validStepResult("architect_handoff"));
            }
            if (message.contains("stepId:\nqa_lead_handoff")) {
                return List.of(validStepResult("qa_lead_handoff"));
            }
            if (message.contains("stepId:\ncompletion")) {
                return List.of(validStepResult("completion"));
            }
            return List.of();
        };
    }

    private Function<String, List<String>> invalidThenValidPlanner() {
        return message -> {
            if (message.startsWith("CORRECTION_PROMPT")) {
                return List.of(validStepResult("scope_slicing"));
            }
            if (message.contains("stepId:\nscope_slicing")) {
                return List.of(invalidOutput());
            }
            return happyPathPlanner().apply(message);
        };
    }

    private Function<String, List<String>> noisyPlanner() {
        return message -> {
            if (message.contains("stepId:\narchitect_handoff")) {
                return List.of(noisyStepResult("architect_handoff"));
            }
            return happyPathPlanner().apply(message);
        };
    }

    private Function<String, List<String>> alwaysInvalidPlanner() {
        return message -> {
            if (message.contains("stepId:\nscope_slicing")) {
                return List.of(invalidOutput());
            }
            if (message.startsWith("CORRECTION_PROMPT")) {
                return List.of(invalidOutput());
            }
            return List.of();
        };
    }

    private static final class InMemoryLaneExecutionRepository implements LaneExecutionRepository {

        private final List<LaneExecution> savedExecutions = new ArrayList<>();
        private final List<LaneExecution> updatedExecutions = new ArrayList<>();
        private final List<LaneStepExecution> savedStepExecutions = new ArrayList<>();

        @Override
        public LaneExecution saveExecution(final LaneExecution execution) {
            this.savedExecutions.add(execution);
            return execution;
        }

        @Override
        public void saveStepExecution(final LaneStepExecution stepExecution) {
            this.savedStepExecutions.add(stepExecution);
        }

        @Override
        public void updateCurrentStep(final LaneExecution execution) {
            this.updatedExecutions.add(execution);
        }

        List<LaneExecution> savedExecutions() {
            return List.copyOf(this.savedExecutions);
        }

        List<LaneStepExecution> savedStepExecutions() {
            return List.copyOf(this.savedStepExecutions);
        }

        List<LaneExecution> updatedExecutions() {
            return List.copyOf(this.updatedExecutions);
        }
    }
}
