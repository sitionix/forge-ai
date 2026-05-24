package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompleteAgentUseCaseTest {

    private CompleteAgentUseCase completeAgentUseCase;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private LaneRepository laneRepository;

    @BeforeEach
    void setUp() {
        this.completeAgentUseCase = new CompleteAgentUseCase(this.ticketRepository, this.laneRepository);
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.ticketRepository, this.laneRepository);
    }

    @Test
    void givenExistingLaneIdAndProducedLanesWithInputTaskIds_whenCompleteAgent_thenUpdateLaneStatusToCompleted() {
        //given
        final UUID laneId = UUID.randomUUID();
        final Lane lane = mock(Lane.class);
        final Lane architectLane = mock(Lane.class);
        final Lane qaLeadLane = mock(Lane.class);
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));
        when(lane.getId()).thenReturn(laneId);
        when(this.laneRepository.findProducedLanes(laneId)).thenReturn(List.of(architectLane, qaLeadLane));
        when(this.laneRepository.findAllByLaneId(laneId)).thenReturn(List.of());
        when(architectLane.getInputTaskIds()).thenReturn(Set.of(UUID.randomUUID()));
        when(qaLeadLane.getInputTaskIds()).thenReturn(Set.of(UUID.randomUUID()));
        when(architectLane.getStatus()).thenReturn(LaneStatus.NOT_STARTED);
        when(qaLeadLane.getStatus()).thenReturn(LaneStatus.NOT_STARTED);
        when(architectLane.getId()).thenReturn(UUID.randomUUID());
        when(qaLeadLane.getId()).thenReturn(UUID.randomUUID());
        when(this.ticketRepository.isReadyToStart(architectLane.getId())).thenReturn(true);
        when(this.ticketRepository.isReadyToStart(qaLeadLane.getId())).thenReturn(true);

        //when
        this.completeAgentUseCase.completeAndPrepareAgents(laneId);

        //then
        verify(this.ticketRepository).findByLaneId(laneId);
        verify(this.laneRepository).findProducedLanes(laneId);
        verify(this.ticketRepository).updateLaneStatus(laneId, LaneStatus.COMPLETED);
        verify(this.ticketRepository).isReadyToStart(architectLane.getId());
        verify(this.ticketRepository).isReadyToStart(qaLeadLane.getId());
        verify(this.ticketRepository).updateLaneStatus(architectLane.getId(), LaneStatus.READY_TO_START);
        verify(this.ticketRepository).updateLaneStatus(qaLeadLane.getId(), LaneStatus.READY_TO_START);
        verify(this.laneRepository).findAllByLaneId(laneId);
        verifyNoMoreInteractions(lane, architectLane, qaLeadLane);
    }

    @Test
    void givenMissingLaneId_whenCompleteAgent_thenThrowException() {
        //given
        final UUID laneId = UUID.randomUUID();
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.empty());

        //when
        //then
        assertThatThrownBy(() -> this.completeAgentUseCase.completeAndPrepareAgents(laneId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Lane not found with id: " + laneId);

        verify(this.ticketRepository).findByLaneId(laneId);
    }

    @Test
    void givenExistingLaneIdAndProducedLanesWithoutInputTaskIds_whenCompleteAgent_thenDoNotUpdateLaneStatusToCompleted() {
        //given
        final UUID laneId = UUID.randomUUID();
        final Lane lane = mock(Lane.class);
        final Lane architectLane = mock(Lane.class);
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));
        when(lane.getId()).thenReturn(laneId);
        when(this.laneRepository.findProducedLanes(laneId)).thenReturn(List.of(architectLane));
        when(architectLane.getInputTaskIds()).thenReturn(Set.of());
        when(architectLane.getStatus()).thenReturn(LaneStatus.NOT_STARTED);

        //when
        this.completeAgentUseCase.completeAndPrepareAgents(laneId);

        //then
        verify(this.ticketRepository).findByLaneId(laneId);
        verify(this.laneRepository).findProducedLanes(laneId);
        verifyNoMoreInteractions(lane, architectLane);
    }

    @Test
    void givenProducedLaneWithNotNeededStatus_whenCompleteAgent_thenTreatAsCompletedAndDoNotSetReadyToStart() {
        //given
        final UUID laneId = UUID.randomUUID();
        final Lane lane = mock(Lane.class);
        final Lane apiLane = mock(Lane.class);
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));
        when(lane.getId()).thenReturn(laneId);
        when(this.laneRepository.findProducedLanes(laneId)).thenReturn(List.of(apiLane));
        when(this.laneRepository.findAllByLaneId(laneId)).thenReturn(List.of());
        when(apiLane.getInputTaskIds()).thenReturn(Set.of());
        when(apiLane.getStatus()).thenReturn(LaneStatus.NOT_NEEDED);

        //when
        this.completeAgentUseCase.completeAndPrepareAgents(laneId);

        //then
        verify(this.ticketRepository).findByLaneId(laneId);
        verify(this.laneRepository).findProducedLanes(laneId);
        verify(this.laneRepository).findAllByLaneId(laneId);
        verify(this.ticketRepository).updateLaneStatus(laneId, LaneStatus.COMPLETED);
        verifyNoMoreInteractions(lane, apiLane);
    }
}
