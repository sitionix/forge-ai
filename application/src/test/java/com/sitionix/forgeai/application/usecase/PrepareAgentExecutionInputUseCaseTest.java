package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.ForgeAiContractApi;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.AgentInstructions;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrepareAgentExecutionInputUseCaseTest {

    private PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase;

    @Mock
    private InstructionRepository instructionRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private ServicePropertiesProvider props;

    @BeforeEach
    void setUp() {
        this.prepareAgentExecutionInputUseCase = new PrepareAgentExecutionInputUseCase(
                this.instructionRepository,
                this.ticketRepository,
                this.props
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.instructionRepository, this.ticketRepository, this.props);
    }

    @Test
    void givenReadyLane_whenExecute_thenBuildAgentExecutionInputAndUpdateLaneStatus() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .agent(Agent.ANALYZER)
                .build();

        final ServicePropertiesProvider.ServiceConfigView serviceConfigView = mock(ServicePropertiesProvider.ServiceConfigView.class);
        final ServicePropertiesProvider.ContractRefView contractRefView = mock(ServicePropertiesProvider.ContractRefView.class);
        when(contractRefView.getRoot()).thenReturn("app-afesox/apis/fgaisox/rest/openapi.yml");
        when(serviceConfigView.getContractRefs()).thenReturn(Map.of("api", contractRefView));
        when(this.props.getServices()).thenReturn(Map.of("forge-ai", serviceConfigView));

        final AgentInstructions instructions = AgentInstructions.builder()
                .agentInstruction("agent-instruction")
                .endpoint("/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/analyzer/complete")
                .additionalInstructions(Set.of("add-1"))
                .sharedInstructions(Set.of("shared-1", "shared-2"))
                .build();
        when(this.instructionRepository.findInstructionsByAgentId("analyzer")).thenReturn(instructions);

        //when
        final AgentExecutionInput actual = this.prepareAgentExecutionInputUseCase.execute(lane);

        //then
        final AgentExecutionInput expected = AgentExecutionInput.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .agentInstruction("agent-instruction")
                .additionalInstructions(Set.of("add-1"))
                .sharedInstructions(Set.of("shared-1", "shared-2"))
                .contractApi(ForgeAiContractApi.builder()
                        .path("app-afesox/apis/fgaisox/rest/openapi.yml")
                        .endpoint("/api/v1/forge-ai/tickets/{ticketId}/lanes/{laneId}/analyzer/complete")
                        .build())
                .build();

        assertThat(actual).isEqualTo(expected);

        verify(this.props).getServices();
        verify(serviceConfigView).getContractRefs();
        verify(contractRefView).getRoot();
        verify(this.instructionRepository).findInstructionsByAgentId("analyzer");
        verify(this.ticketRepository).updateLaneStatus(laneId, LaneStatus.IN_PROGRESS);
        verifyNoMoreInteractions(serviceConfigView, contractRefView);
    }
}
