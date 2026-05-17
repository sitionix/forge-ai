package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;

import java.util.UUID;

@FunctionalInterface
public interface CreateAgentTask {

     <P extends AgentTicketPayload> void create(final AgentTicket<P> agentTicket, final UUID sourceLaneId);
}
