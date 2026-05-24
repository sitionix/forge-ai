package com.sitionix.forgeai.api.usecase;

import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.TicketStatus;
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
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompleteReviewerTaskUseCaseTest {

    @Mock
    private TicketRepository ticketRepository;

    private Ticket savedTicket;

    private CompleteReviewerTaskUseCase completeReviewerTaskUseCase;

    @BeforeEach
    void setUp() {
        this.completeReviewerTaskUseCase = new CompleteReviewerTaskUseCase(this.ticketRepository);
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.ticketRepository);
    }

    @Test
    void givenReviewerLaneWhenComplete_thenCompleteReviewerAndTicket() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID reviewerLaneId = UUID.randomUUID();
        final Ticket ticket = this.getTicket(reviewerLaneId, LaneStatus.READY_TO_START);
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(this.ticketRepository.save(ticket)).thenAnswer(invocation -> {
            this.savedTicket = invocation.getArgument(0);
            return this.savedTicket;
        });

        //when
        final UUID actual = this.completeReviewerTaskUseCase.complete(ticketId);

        //then
        verify(this.ticketRepository).findById(ticketId);
        verify(this.ticketRepository).save(ticket);
        assertThat(actual).isEqualTo(reviewerLaneId);
        assertThat(this.savedTicket.getStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(this.savedTicket.getLanes()).hasSize(1);
        assertThat(this.savedTicket.getLanes().getFirst().getStatus()).isEqualTo(LaneStatus.COMPLETED);
    }

    private Ticket getTicket(final UUID reviewerLaneId, final LaneStatus status) {
        return Ticket.builder()
                .id(UUID.randomUUID())
                .status(TicketStatus.OPEN)
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
}
