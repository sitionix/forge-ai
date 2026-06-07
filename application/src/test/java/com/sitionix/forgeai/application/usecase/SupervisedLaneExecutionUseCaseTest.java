package com.sitionix.forgeai.application.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.agentexecutor.LaneCompletionContractResolver;
import com.sitionix.forgeai.application.laneexecution.CompletionPayloadContractBuilder;
import com.sitionix.forgeai.application.laneexecution.CompletionPayloadContractRenderer;
import com.sitionix.forgeai.application.laneexecution.LaneCompletionDispatcher;
import com.sitionix.forgeai.application.laneexecution.LaneExecutionProgressService;
import com.sitionix.forgeai.application.laneexecution.LaneStepDoneResultParser;
import com.sitionix.forgeai.application.laneexecution.LaneStepPromptBuilder;
import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.laneexecution.support.FakeInteractiveCodexSessionRepository;
import com.sitionix.forgeai.application.laneexecution.validation.LaneStepEvidenceValidatorRegistry;
import com.sitionix.forgeai.application.operator.TicketOperatorRunService;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.CodexLaneWorkspace;
import com.sitionix.forgeai.domain.model.codex.CodexSession;
import com.sitionix.forgeai.domain.model.codex.CodexSessionStartCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnResponse;
import com.sitionix.forgeai.domain.model.codex.ScopeContext;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecutionStatus;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadObjectContract;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorRun;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorRunStatus;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.CompletionPayloadContractRepository;
import com.sitionix.forgeai.domain.repository.CodexSessionRepository;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import com.sitionix.forgeai.domain.repository.LaneExecutionRepository;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.repository.LaneStrategyRepository;
import com.sitionix.forgeai.domain.usecase.ManageTicketOperatorRuns;
import com.sitionix.forgeai.domain.usecase.ResolveCodexLaneWorkspace;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupervisedLaneExecutionUseCaseTest {

    @Mock
    private LaneStrategyRepository laneStrategyRepository;
    @Mock
    private LaneCompletionDispatcher laneCompletionDispatcher;
    @Mock
    private LaneRepository laneRepository;
    @Mock
    private TicketOperatorRunService ticketOperatorRunService;
    @Mock
    private ManageTicketOperatorRuns manageTicketOperatorRuns;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LaneExecutionRepository laneExecutionRepository;
    private SupervisedExecutionProperties supervisedExecutionProperties;
    private LaneExecutionProgressService laneExecutionProgressService;

    @BeforeEach
    void setUp() {
        this.laneExecutionRepository = new InMemoryLaneExecutionRepository();
        this.supervisedExecutionProperties = new SupervisedExecutionProperties();
        this.supervisedExecutionProperties.setTurnTimeout(Duration.ofSeconds(1));
        this.laneExecutionProgressService = new LaneExecutionProgressService(this.laneExecutionRepository, this.ticketOperatorRunService);
        when(this.manageTicketOperatorRuns.isExecutionBlocked(any())).thenReturn(false);
        lenient().when(this.laneRepository.findProducedLanes(any())).thenReturn(List.of());
        lenient().when(this.ticketOperatorRunService.markCompletedIfTerminal(any())).thenReturn(TicketOperatorRun.builder()
                .ticketId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                .status(TicketOperatorRunStatus.WATCHING)
                .build());
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

    @Test
    void givenResolvedLaneWorkspace_whenExecute_thenOpenCodexSessionWithResolvedCwdAndRoots() {
        when(this.laneStrategyRepository.findByAgentId("analyzer")).thenReturn(this.strategy());
        final FakeInteractiveCodexSessionRepository sessions = new FakeInteractiveCodexSessionRepository(command -> this.validResult(this.stepIdFromPrompt(command.prompt())));
        final SupervisedLaneExecutionUseCase useCase = this.useCase(
                sessions,
                lane -> new CodexLaneWorkspace("/workspace/backendforfrontendservice-sox", List.of(
                        "/workspace/backendforfrontendservice-sox",
                        "/workspace/app-afesox"
                ))
        );

        useCase.execute(this.lane(), this.input(), 1);

        assertThat(sessions.openSessionCommands()).singleElement().satisfies(command -> {
            assertThat(command.workspaceRoot()).isEqualTo("/workspace/backendforfrontendservice-sox");
            assertThat(command.runtimeWorkspaceRoots()).containsExactly(
                    "/workspace/backendforfrontendservice-sox",
                    "/workspace/app-afesox"
            );
        });
    }

    private SupervisedLaneExecutionUseCase useCase(final CodexSessionRepository sessions) {
        return this.useCase(sessions, lane -> new CodexLaneWorkspace(System.getProperty("user.dir"), List.of(System.getProperty("user.dir"))));
    }

    private SupervisedLaneExecutionUseCase useCase(final CodexSessionRepository sessions,
                                                   final ResolveCodexLaneWorkspace resolveCodexLaneWorkspace) {
        final FakeLaneCompletionContractResolver completionContractResolver = new FakeLaneCompletionContractResolver();
        final FakeCompletionPayloadContractRepository completionPayloadContractRepository = new FakeCompletionPayloadContractRepository();
        return new SupervisedLaneExecutionUseCase(
                this.laneStrategyRepository,
                this.laneExecutionRepository,
                sessions,
                new LaneStepPromptBuilder(
                        () -> List.of("shared/common-rules.md"),
                        new FakeInstructionRepository(),
                        new CompletionPayloadContractBuilder(this.laneRepository, completionContractResolver, completionPayloadContractRepository),
                        new CompletionPayloadContractRenderer(this.objectMapper, completionPayloadContractRepository),
                        this.objectMapper
                ),
                new LaneStepDoneResultParser(this.objectMapper),
                this.laneCompletionDispatcher,
                this.laneExecutionProgressService,
                this.supervisedExecutionProperties,
                this.objectMapper,
                this.manageTicketOperatorRuns,
                resolveCodexLaneWorkspace,
                new LaneStepEvidenceValidatorRegistry(this.objectMapper, List.of())
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
                        LaneStrategyStep.builder().id("scope_slicing").title("Scope Slicing").order(1).taskPlaceholder("TASKS").instructionRefs(List.of("lane-instructions/analyzer/scope-slicing.md")).build(),
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
                ? "\"completionPayload\":{\"outputs\":[]}"
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
        public String findInstructionTextByRef(final String instructionRef) {
            return "resolved::" + instructionRef;
        }

        @Override
        public Set<String> findSharedInstructionRefs() {
            return Set.of("shared/common-rules.md");
        }
    }

    private static final class FakeLaneCompletionContractResolver implements LaneCompletionContractResolver {

        @Override
        public Class<? extends AgentTicketPayload> inputPayloadType(final Agent sourceAgent, final Agent targetAgent) {
            return AgentTicketPayload.class;
        }

        @Override
        public boolean writesProducedLaneOutputs(final Agent agent) {
            return true;
        }

        @Override
        public boolean requiresApiCompletionEvidence(final Agent agent) {
            return false;
        }

        @Override
        public boolean requiresCompletionOutputForEveryTarget(final Agent agent) {
            return true;
        }

        @Override
        public Optional<Class<? extends AgentTicketPayload>> completionReportPayloadType(final Agent agent) {
            return Optional.empty();
        }
    }

    private static final class FakeCompletionPayloadContractRepository implements CompletionPayloadContractRepository {

        @Override
        public CompletionPayloadObjectContract findByType(final Class<?> payloadType) {
            return this.findByTypeName(payloadType.getSimpleName());
        }

        @Override
        public CompletionPayloadObjectContract findByTypeName(final String payloadType) {
            return new CompletionPayloadObjectContract(payloadType, "Fake completion payload contract.", List.of());
        }
    }

    private static final class TimeoutCodexSessionRepository implements CodexSessionRepository {
        @Override
        public CodexSession openSession(final CodexSessionStartCommand command) {
            return CodexSession.builder()
                    .id("session")
                    .threadId("thread")
                    .processPid(42L)
                    .command(List.of("codex", "app-server", "--stdio"))
                    .cwd(System.getProperty("user.dir"))
                    .startedAt(Instant.now())
                    .codexVersion("fake")
                    .build();
        }

        @Override
        public CodexTurnResponse submitTurn(final String sessionId, final CodexTurnCommand command) {
            throw new IllegalStateException("Timed out waiting for Codex turn");
        }

        @Override
        public void closeSession(final String sessionId) {
        }

        @Override
        public void interruptTurn(final String sessionId, final String turnId, final Duration timeout) {
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

        @Override
        public Optional<LaneExecution> findExecution(final UUID executionId) {
            return this.savedExecutions.stream()
                    .filter(execution -> executionId.equals(execution.getId()))
                    .reduce((first, second) -> second);
        }

        @Override
        public List<LaneStepExecution> findStepExecutions(final UUID executionId) {
            return this.savedStepExecutions.stream()
                    .filter(stepExecution -> executionId.equals(stepExecution.getExecutionId()))
                    .toList();
        }

        @Override
        public List<LaneExecution> findByTicketId(final UUID ticketId) {
            return this.savedExecutions.stream()
                    .filter(execution -> ticketId.equals(execution.getTicketId()))
                    .toList();
        }

        @Override
        public List<LaneExecution> findActiveExecutions() {
            return this.savedExecutions.stream()
                    .filter(execution -> execution.getStatus() == null || !execution.getStatus().isTerminal())
                    .toList();
        }

        @Override
        public List<LaneExecution> findActiveExecutionsByTicketId(final UUID ticketId) {
            return this.savedExecutions.stream()
                    .filter(execution -> ticketId.equals(execution.getTicketId()))
                    .filter(execution -> execution.getStatus() == null || !execution.getStatus().isTerminal())
                    .toList();
        }

        @Override
        public void deleteByTicketId(final UUID ticketId) {
            final List<UUID> executionIds = this.savedExecutions.stream()
                    .filter(execution -> ticketId.equals(execution.getTicketId()))
                    .map(LaneExecution::getId)
                    .toList();
            this.savedExecutions.removeIf(execution -> ticketId.equals(execution.getTicketId()));
            this.savedStepExecutions.removeIf(stepExecution -> executionIds.contains(stepExecution.getExecutionId()));
        }

        List<LaneExecution> savedExecutions() {
            return this.savedExecutions;
        }

        List<LaneStepExecution> savedStepExecutions() {
            return this.savedStepExecutions;
        }
    }
}
