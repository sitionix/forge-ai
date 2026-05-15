package com.sitionix.forgeai.infrastructure.mongodb.adapter;

import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.port.TicketRepository;
import com.sitionix.forgeai.infrastructure.mongodb.TicketEntityMapper;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.repository.TicketJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TicketRepositoryImpl implements TicketRepository {

    private final TicketJpaRepository ticketRepository;
    private final TicketEntityMapper ticketEntityMapper;

    @Override
    public Ticket save(final Ticket ticket) {
        final TicketDocument document = ticketEntityMapper.asTicketDocument(ticket);
        final TicketDocument saved = ticketRepository.save(document);
        return ticketEntityMapper.asTicket(saved);
    }
}
