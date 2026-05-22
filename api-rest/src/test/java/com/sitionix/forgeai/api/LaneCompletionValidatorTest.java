package com.sitionix.forgeai.api;

import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.List;
import java.util.Optional;
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
class LaneCompletionValidatorTest {

    private LaneCompletionValidator laneCompletionValidator;

    @Mock
    private TicketRepository ticketRepository;

    @BeforeEach
    void setUp() {
        this.laneCompletionValidator = new LaneCompletionValidator(this.ticketRepository);
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.ticketRepository);
    }

    @Test
    void givenMissingTicket_whenValidateItTestCompletion_thenThrowTicketNotFoundException() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.empty());

        //when //then
        assertThatThrownBy(() -> this.laneCompletionValidator.validateItTestCompletion(ticketId, laneId, "automationservice-sox"))
                .isInstanceOf(TicketNotFoundException.class)
                .hasMessageContaining("Ticket not found with ticketId=");

        verify(this.ticketRepository).findById(ticketId);
    }

    @Test
    void givenMissingLane_whenValidateQaLeadCompletion_thenThrowLaneNotFoundException() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final Ticket ticket = mock(Ticket.class);
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(ticket.getLanes()).thenReturn(List.of());

        //when //then
        assertThatThrownBy(() -> this.laneCompletionValidator.validateQaLeadCompletion(ticketId, laneId, "automationservice-sox"))
                .isInstanceOf(LaneNotFoundException.class)
                .hasMessageContaining("QA lead lane not found");

        verify(this.ticketRepository).findById(ticketId);
    }

    @Test
    void givenWrongLaneType_whenValidateItTestCompletion_thenThrowLaneConflictException() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final Ticket ticket = mock(Ticket.class);
        final Lane lane = mock(Lane.class);
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));
        when(ticket.getLanes()).thenReturn(List.of(lane));
        when(lane.getId()).thenReturn(laneId);
        when(lane.getAgent()).thenReturn(Agent.TEST_UNIT);

        //when //then
        assertThatThrownBy(() -> this.laneCompletionValidator.validateItTestCompletion(ticketId, laneId, "automationservice-sox"))
                .isInstanceOf(LaneConflictException.class)
                .hasMessageContaining("lane type mismatch");

        verify(this.ticketRepository).findById(ticketId);
        verify(this.ticketRepository).findByLaneId(laneId);
    }

    @Test
    void givenWrongScope_whenValidateItTestCompletion_thenThrowLaneConflictException() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final Ticket ticket = mock(Ticket.class);
        final Lane lane = mock(Lane.class);
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));
        when(ticket.getLanes()).thenReturn(List.of(lane));
        when(lane.getId()).thenReturn(laneId);
        when(lane.getAgent()).thenReturn(Agent.TEST_IT);
        when(lane.getScope()).thenReturn("backendforfrontendservice-sox");

        //when //then
        assertThatThrownBy(() -> this.laneCompletionValidator.validateItTestCompletion(ticketId, laneId, "automationservice-sox"))
                .isInstanceOf(LaneConflictException.class)
                .hasMessageContaining("scope mismatch");

        verify(this.ticketRepository).findById(ticketId);
        verify(this.ticketRepository).findByLaneId(laneId);
    }

    @Test
    void givenCompletedLane_whenValidateItTestCompletion_thenThrowLaneConflictException() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final Ticket ticket = mock(Ticket.class);
        final Lane lane = mock(Lane.class);
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));
        when(ticket.getLanes()).thenReturn(List.of(lane));
        when(lane.getId()).thenReturn(laneId);
        when(lane.getAgent()).thenReturn(Agent.TEST_IT);
        when(lane.getScope()).thenReturn("automationservice-sox");
        when(lane.getStatus()).thenReturn(LaneStatus.COMPLETED);

        //when //then
        assertThatThrownBy(() -> this.laneCompletionValidator.validateItTestCompletion(ticketId, laneId, "automationservice-sox"))
                .isInstanceOf(LaneConflictException.class)
                .hasMessageContaining("current state");

        verify(this.ticketRepository).findById(ticketId);
        verify(this.ticketRepository).findByLaneId(laneId);
    }
}
