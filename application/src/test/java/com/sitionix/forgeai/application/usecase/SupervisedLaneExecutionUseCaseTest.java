package com.sitionix.forgeai.application.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.laneexecution.LaneStepPromptBuilder;
import com.sitionix.forgeai.application.laneexecution.LaneStepDoneResultParser;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.CodexSessionRepository;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import com.sitionix.forgeai.domain.repository.LaneExecutionRepository;
import com.sitionix.forgeai.domain.repository.LaneStrategyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupervisedLaneExecutionUseCaseTest {

    @Mock
    private LaneStrategyRepository laneStrategyRepository;
    @Mock
    private LaneExecutionRepository laneExecutionRepository;
    @Mock
    private CodexSessionRepository codexSessionRepository;
    @Mock
    private InstructionRepository instructionRepository;

    private SupervisedLaneExecutionUseCase useCase;

    @BeforeEach
    void setUp() {
        this.useCase = new SupervisedLaneExecutionUseCase(
                this.laneStrategyRepository,
                this.laneExecutionRepository,
                this.codexSessionRepository,
                this.instructionRepository,
                new LaneStepDoneResultParser(new ObjectMapper()),
                new LaneStepPromptBuilder(new ObjectMapper()),
                new ObjectMapper()
        );
    }

    @Test
    void givenValidOutputs_whenExecute_thenSendStepsInOrderAndPersistDone() {
        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(UUID.randomUUID())
                .laneId(UUID.randomUUID())
                .agent(Agent.API)
                .scope("backendforfrontendservice-sox")
                .serviceId("bffssox")
                .sourceTerminalTty("/dev/ttys001")
                .build();

        final LaneStrategy strategy = LaneStrategy.builder()
                .agentId("api")
                .version(1)
                .sessionMode("single_session")
                .steps(List.of(
                        LaneStrategyStep.builder().id("preparation").title("Preparation").order(1).instructionRefs(List.of("a.md")).build(),
                        LaneStrategyStep.builder().id("contract_update").title("Contract").order(2).instructionRefs(List.of("b.md")).build()
                ))
                .build();

        when(this.laneStrategyRepository.findByAgentId("api")).thenReturn(strategy);
        when(this.instructionRepository.findSharedInstructionRefs()).thenReturn(Set.of("instructions/shared/common-rules.md"));
        when(this.codexSessionRepository.start(any(), eq("/dev/ttys001"))).thenReturn("session-1");
        when(this.laneExecutionRepository.saveExecution(any(LaneExecution.class))).thenAnswer(inv -> inv.getArgument(0));
        when(this.codexSessionRepository.waitForOutput(eq("session-1"), anyLong()))
                .thenReturn("{\"type\":\"LANE_STEP_DONE\",\"stepId\":\"preparation\",\"summary\":\"done\",\"evidence\":{}}")
                .thenReturn("{\"type\":\"LANE_STEP_DONE\",\"stepId\":\"contract_update\",\"summary\":\"done\",\"evidence\":{}}");

        final AgentExecutionInput<AgentTicketPayload> input = AgentExecutionInput.<AgentTicketPayload>builder()
                .tasks(Set.of())
                .build();
        this.useCase.execute(lane, input, 1);

        final InOrder order = inOrder(this.codexSessionRepository);
        order.verify(this.codexSessionRepository).start(any(), eq("/dev/ttys001"));
        order.verify(this.codexSessionRepository).waitForOutput(eq("session-1"), anyLong());
        order.verify(this.codexSessionRepository).send(eq("session-1"), any(), eq("/dev/ttys001"));
        order.verify(this.codexSessionRepository).waitForOutput(eq("session-1"), anyLong());
        order.verify(this.codexSessionRepository).close("session-1");

        verify(this.laneExecutionRepository, atLeastOnce()).saveStepExecution(any(LaneStepExecution.class));
    }

    @Test
    void givenInvalidThenValidOutput_whenExecute_thenSendCorrectionAndProceed() {
        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(UUID.randomUUID())
                .laneId(UUID.randomUUID())
                .agent(Agent.API)
                .scope("backendforfrontendservice-sox")
                .serviceId("bffssox")
                .sourceTerminalTty("/dev/ttys001")
                .build();
        final LaneStrategy strategy = LaneStrategy.builder()
                .agentId("api")
                .version(1)
                .sessionMode("single_session")
                .steps(List.of(LaneStrategyStep.builder().id("preparation").title("Preparation").order(1).instructionRefs(List.of("a.md")).build()))
                .build();
        when(this.laneStrategyRepository.findByAgentId("api")).thenReturn(strategy);
        when(this.instructionRepository.findSharedInstructionRefs()).thenReturn(Set.of("instructions/shared/common-rules.md"));
        when(this.codexSessionRepository.start(any(), eq("/dev/ttys001"))).thenReturn("session-1");
        when(this.laneExecutionRepository.saveExecution(any(LaneExecution.class))).thenAnswer(inv -> inv.getArgument(0));
        when(this.codexSessionRepository.waitForOutput(eq("session-1"), anyLong()))
                .thenReturn("not-json")
                .thenReturn("{\"type\":\"LANE_STEP_DONE\",\"stepId\":\"preparation\",\"summary\":\"done\",\"evidence\":{}}");

        final AgentExecutionInput<AgentTicketPayload> input = AgentExecutionInput.<AgentTicketPayload>builder()
                .tasks(Set.of())
                .build();
        this.useCase.execute(lane, input, 2);

        final ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(this.codexSessionRepository, atLeastOnce()).send(eq("session-1"), promptCaptor.capture(), eq("/dev/ttys001"));
    }
}
