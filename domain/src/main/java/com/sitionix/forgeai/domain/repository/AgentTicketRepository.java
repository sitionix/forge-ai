package com.sitionix.forgeai.domain.repository;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;

import java.util.Optional;
import java.util.UUID;

/**
 * Persists agent tickets generated from lane completion.
 */
public interface AgentTicketRepository {

    <P extends AgentTicketPayload> AgentTicket<P> save(AgentTicket<P> agentTicket);

    Optional<AgentTicket<AgentTicketPayload>> findById(UUID id);

    <P extends AgentTicketPayload> Optional<AgentTicket<P>> findById(UUID id, Class<P> payloadType);
}
