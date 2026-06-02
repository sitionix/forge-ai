package com.sitionix.forgeai.application.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.laneexecution.LaneCompletionDispatcher;
import com.sitionix.forgeai.application.laneexecution.LaneStepDoneResultParser;
import com.sitionix.forgeai.application.laneexecution.LaneStepPromptBuilder;
import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.laneexecution.support.FakeInteractiveCodexSessionRepository;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.CodexSession;
import com.sitionix.forgeai.domain.model.codex.CodexSessionStartCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnResponse;
import com.sitionix.forgeai.domain.model.codex.ScopeContext;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.AgentInstructions;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.CodexSessionRepository;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import com.sitionix.forgeai.domain.repository.LaneExecutionRepository;
import com.sitionix.forgeai.domain.repository.LaneStrategyRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupervisedLaneExecutionUseCaseTest {

    @Mock
    private LaneStrategyRepository laneStrategyRepository;
    @Mock
    private LaneCompletionDispatcher laneCompletionDispatcher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LaneExecutionRepository laneExecutionRepository;
    private SupervisedExecutionProperties supervisedExecutionProperties;

    @BeforeEach
    void setUp() {
        this.laneExecutionRepository = new InMemoryLaneExecutionRepository();
        this.supervisedExecutionProperties = new SupervisedExecutionProperties();
        this.supervisedExecutionProperties.setTurnTimeout(Duration.ofSeconds(1));
    }

    @Test
    void givenValidResponses_whenExecute_thenPersistEveryStepAndDispatchFinalCompletion() {
        when(this.laneStrategyRepository.findByAgentId("analyzer")).thenReturn(this.strategy());
        final FakeInteractiveCodexSessionRepository sessions = new FakeInteractiveCodexSessionRepository(command -> this.validResult(this.stepIdFromPrompt(command.prompt())));
        final SupervisedLaneExecutionUseCase useCase = this.useCase(sessions);

        useCase.execute(this.lane(), this.input(), 1);

        assertThat(sessions.submittedPrompts()).hasSize(3);
        assertThat(((InMemoryLaneExecutionRepository) this.laneExecutionRepository).savedStepExecutions())
                .extracting(LaneStepExecution::getStepId)
                .containsExactly("scope_slicing", "architect_handoff", "completion");
        verify(this.laneCompletionDispatcher).validateFinalCompletionPayload(eq(this.lane()), any());
        verify(this.laneCompletionDispatcher).completeLane(eq(this.lane()), any());
    }

    @Test
    void givenInvalidFirstResponse_whenExecute_thenSendCorrectionForSameStep() {
        when(this.laneStrategyRepository.findByAgentId("analyzer")).thenReturn(this.strategy());
        final AtomicBoolean invalidReturned = new AtomicBoolean(false);
        final FakeInteractiveCodexSessionRepository sessions = new FakeInteractiveCodexSessionRepository(command -> {
            if (!invalidReturned.getAndSet(true)) {
                return "not json";
            }
            return this.validResult(this.stepIdFromPrompt(command.prompt()));
        });
        final SupervisedLaneExecutionUseCase useCase = this.useCase(sessions);

        useCase.execute(this.lane(), this.input(), 1);

        assertThat(sessions.submittedPrompts()).anyMatch(prompt -> prompt.contains("CORRECTION_PROMPT"));
        assertThat(((InMemoryLaneExecutionRepository) this.laneExecutionRepository).savedStepExecutions()).isNotEmpty();
    }

    @Test
    void givenTimeout_whenExecute_thenDoNotPersistOrCompleteLane() {
        when(this.laneStrategyRepository.findByAgentId("analyzer")).thenReturn(this.strategy());
        final SupervisedLaneExecutionUseCase useCase = this.useCase(new TimeoutCodexSessionRepository());

        useCase.execute(this.lane(), this.input(), 1);

        assertThat(((InMemoryLaneExecutionRepository) this.laneExecutionRepository).savedStepExecutions()).isEmpty();
        verify(this.laneCompletionDispatcher, never()).completeLane(any(), any());
    }

    private SupervisedLaneExecutionUseCase useCase(final CodexSessionRepository sessions) {
        return new SupervisedLaneExecutionUseCase(
                this.laneStrategyRepository,
                this.laneExecutionRepository,
                sessions,
                new LaneStepPromptBuilder(() -> List.of("shared/common-rules.md"), new FakeInstructionRepository(), this.objectMapper),
                new LaneStepDoneResultParser(this.objectMapper),
                this.laneCompletionDispatcher,
                this.supervisedExecutionProperties,
                this.objectMapper
        );
    }

    private ReadyToStartLane lane() {
        return ReadyToStartLane.builder()
                .ticketId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                .ticketKey("SITIONIX-1")
                .laneId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
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
        final ApiPayload task = ApiPayload.builder().scope("backendforfrontendservice-sox").summary("summary").build();
        return AgentExecutionInput.<AgentTicketPayload>builder()
                .tasks(new LinkedHashSet<>(Set.of(task)))
                .scope(ScopeContext.builder().scope("backendforfrontendservice-sox").build())
                .build();
    }

    private String stepIdFromPrompt(final String prompt) {
        if (prompt.contains("scope_slicing")) {
            return "scope_slicing";
        }
        if (prompt.contains("architect_handoff")) {
            return "architect_handoff";
        }
        return "completion";
    }

    private String validResult(final String stepId) {
        final String evidence = "completion".equals(stepId)
                ? "\"completionPayload\":{\"architectHandoff\":{\"scope\":\"backendforfrontendservice-sox\",\"requirements\":[],\"constraints\":[],\"nonGoals\":[],\"risks\":[],\"dependencies\":[]},\"qaLeadHandoff\":{\"scope\":\"backendforfrontendservice-sox\",\"requirements\":[],\"constraints\":[],\"nonGoals\":[],\"risks\":[],\"dependencies\":[],\"qualityFocus\":[],\"edgeConsiderations\":[]}}"
                : "\"detail\":\"ok\"";
        return """
                {
                  "type": "LANE_STEP_DONE",
                  "stepId": "%s",
                  "summary": "done",
                  "evidence": { %s }
                }
                """.formatted(stepId, evidence);
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

    private static final class TimeoutCodexSessionRepository implements CodexSessionRepository {
        @Override
        public CodexSession openSession(final CodexSessionStartCommand command) {
            return CodexSession.builder().id("session").threadId("thread").build();
        }

        @Override
        public CodexTurnResponse submitTurn(final String sessionId, final CodexTurnCommand command) {
            throw new IllegalStateException("Timed out waiting for Codex turn");
        }

        @Override
        public void closeSession(final String sessionId) {
        }
    }

    private static final class InMemoryLaneExecutionRepository implements LaneExecutionRepository {
        private final List<LaneExecution> savedExecutions = new ArrayList<>();
        private final List<LaneStepExecution> savedStepExecutions = new ArrayList<>();
        private final List<LaneExecution> updatedExecutions = new ArrayList<>();

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
            return this.savedExecutions;
        }

        List<LaneStepExecution> savedStepExecutions() {
            return this.savedStepExecutions;
        }
    }
}
