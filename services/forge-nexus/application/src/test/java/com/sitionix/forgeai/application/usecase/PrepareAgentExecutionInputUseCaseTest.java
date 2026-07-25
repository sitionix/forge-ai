package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrepareAgentExecutionInputUseCaseTest {

    private PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private ServicePropertiesProvider props;

    @BeforeEach
    void setUp() {
        this.prepareAgentExecutionInputUseCase = new PrepareAgentExecutionInputUseCase(
                this.ticketRepository,
                this.props
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.ticketRepository, this.props);
    }

    @Test
    void givenReadyLane_whenExecute_thenBuildAgentExecutionInputAndUpdateLaneStatus() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(ticketId)
                .ticketKey("SITIONIX-1")
                .laneId(laneId)
                .agent(Agent.ANALYZER)
                .build();

        when(this.ticketRepository.moveLaneToInProgressIfReady(laneId)).thenReturn(true);

        //when
        final AgentExecutionInput actual = this.prepareAgentExecutionInputUseCase.execute(lane);

        //then
        final AgentExecutionInput expected = AgentExecutionInput.builder()
                .ticketId(ticketId)
                .ticket("SITIONIX-1")
                .laneId(laneId)
                .build();

        assertThat(actual).isEqualTo(expected);

        verify(this.ticketRepository).moveLaneToInProgressIfReady(laneId);
    }

    @Test
    void givenNotReadyLane_whenExecute_thenThrowExceptionAndDoNotBuildInput() {
        //given
        final UUID laneId = UUID.randomUUID();
        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(UUID.randomUUID())
                .ticketKey("SITIONIX-1")
                .laneId(laneId)
                .agent(Agent.ANALYZER)
                .build();

        when(this.ticketRepository.moveLaneToInProgressIfReady(laneId)).thenReturn(false);

        //when
        //then
        assertThatThrownBy(() -> this.prepareAgentExecutionInputUseCase.execute(lane))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Lane is not ready to start or already started: laneId=" + laneId);

        verify(this.ticketRepository).moveLaneToInProgressIfReady(laneId);
        verifyNoMoreInteractions(this.props);
    }
}
