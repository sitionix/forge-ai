package com.sitionix.forgeai.infrastructure.mongodb.adapter;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.infrastructure.mongodb.AgentTicketEntityMapper;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.repository.AgentTicketJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentTicketRepositoryImpl implements AgentTicketRepository {

    private final AgentTicketJpaRepository agentTicketJpaRepository;

    private final AgentTicketEntityMapper agentTicketEntityMapper;

    @Override
    public <P extends AgentTicketPayload> AgentTicket<P> save(final AgentTicket<P> agentTicket) {
        final AgentTicketDocument saved = this.agentTicketJpaRepository.save(
                this.agentTicketEntityMapper.asAgentTicketDocument(agentTicket)
        );
        return this.agentTicketEntityMapper.asAgentTicket(saved);
    }
}
