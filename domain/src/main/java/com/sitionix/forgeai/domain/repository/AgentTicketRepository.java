package com.sitionix.forgeai.domain.repository;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;

/**
 * Persists agent tickets generated from lane completion callbacks.
 */
public interface AgentTicketRepository {

    <P extends AgentTicketPayload> AgentTicket<P> save(AgentTicket<P> agentTicket);
}
