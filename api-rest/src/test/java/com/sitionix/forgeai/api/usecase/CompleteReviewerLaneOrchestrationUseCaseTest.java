package com.sitionix.forgeai.api.usecase;

import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompleteReviewerLaneOrchestrationUseCaseTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private CompleteAgentTasks completeAgentTasks;

    private CompleteReviewerLaneOrchestrationUseCase completeReviewerLaneOrchestrationUseCase;

    @BeforeEach
    void setUp() {
        this.completeReviewerLaneOrchestrationUseCase = new CompleteReviewerLaneOrchestrationUseCase(this.ticketRepository, this.completeAgentTasks);
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.ticketRepository, this.completeAgentTasks);
    }

    @Test
    void givenReviewerInProgress_whenComplete_thenCompleteWithoutDownstreamTasks() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID reviewerLaneId = UUID.randomUUID();
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.of(this.getTicket(reviewerLaneId, LaneStatus.IN_PROGRESS)));

        //when
        final UUID actual = this.completeReviewerLaneOrchestrationUseCase.complete(ticketId);

        //then
        verify(this.ticketRepository).findById(ticketId);
        verify(this.completeAgentTasks).complete(reviewerLaneId, List.of());
        assertThat(actual).isEqualTo(reviewerLaneId);
    }

    @Test
    void givenReviewerNotFound_whenComplete_thenThrowNotFound() {
        //given
        final UUID ticketId = UUID.randomUUID();
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.of(this.getTicketWithoutReviewer()));

        //when
        //then
        assertThatThrownBy(() -> this.completeReviewerLaneOrchestrationUseCase.complete(ticketId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        verify(this.ticketRepository).findById(ticketId);
    }

    @Test
    void givenReviewerInNotReadyState_whenComplete_thenThrowConflict() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID reviewerLaneId = UUID.randomUUID();
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.of(this.getTicket(reviewerLaneId, LaneStatus.READY_TO_START)));

        //when
        //then
        assertThatThrownBy(() -> this.completeReviewerLaneOrchestrationUseCase.complete(ticketId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
        verify(this.ticketRepository).findById(ticketId);
    }

    private Ticket getTicket(final UUID reviewerLaneId, final LaneStatus status) {
        return Ticket.builder()
                .id(UUID.randomUUID())
                .lanes(List.of(
                        Lane.builder()
                                .id(reviewerLaneId)
                                .agent(Agent.REVIEWER)
                                .scope("GLOBAL")
                                .status(status)
                                .build()
                ))
                .build();
    }

    private Ticket getTicketWithoutReviewer() {
        return Ticket.builder()
                .id(UUID.randomUUID())
                .lanes(List.of(
                        Lane.builder()
                                .id(UUID.randomUUID())
                                .agent(Agent.TEST_UNIT)
                                .scope("automationservice-sox")
                                .status(LaneStatus.COMPLETED)
                                .build()
                ))
                .build();
    }
}
