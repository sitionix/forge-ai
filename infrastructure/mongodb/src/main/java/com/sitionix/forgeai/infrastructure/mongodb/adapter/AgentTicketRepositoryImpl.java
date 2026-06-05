package com.sitionix.forgeai.infrastructure.mongodb.adapter;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.infrastructure.mongodb.AgentTicketEntityMapper;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.repository.AgentTicketJpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentTicketRepositoryImpl implements AgentTicketRepository {

    private final AgentTicketJpaRepository agentTicketJpaRepository;

    private final AgentTicketEntityMapper agentTicketEntityMapper;

    @Override
    @SuppressWarnings("unchecked")
    public <P extends AgentTicketPayload> AgentTicket<P> save(final AgentTicket<P> agentTicket) {
        final AgentTicketDocument saved = this.agentTicketJpaRepository.save(
                this.agentTicketEntityMapper.asAgentTicketDocument(agentTicket)
        );
        return (AgentTicket<P>) this.agentTicketEntityMapper.asAgentTicket(saved);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<AgentTicket<AgentTicketPayload>> findById(final UUID id) {
        return this.agentTicketJpaRepository.findById(id)
                .map(this.agentTicketEntityMapper::asAgentTicket)
                .map(value -> (AgentTicket<AgentTicketPayload>) value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<AgentTicket<AgentTicketPayload>> findByIds(final Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return this.agentTicketJpaRepository.findAllById(ids).stream()
                .map(this.agentTicketEntityMapper::asAgentTicket)
                .map(value -> (AgentTicket<AgentTicketPayload>) value)
                .toList();
    }

    @Override
    public void deleteByTicketId(final UUID ticketId) {
        this.agentTicketJpaRepository.deleteByTicketId(ticketId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <P extends AgentTicketPayload> Optional<AgentTicket<P>> findById(final UUID id, final Class<P> payloadType) {
        return this.findById(id)
                .filter(ticket -> payloadType.isInstance(ticket.getPayload()))
                .map(ticket -> (AgentTicket<P>) ticket);
    }
}
