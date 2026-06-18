package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import java.util.UUID;

public interface CreateAgentTask {

     <P extends AgentTicketPayload> void create(final AgentTicket<P> agentTicket, final UUID sourceLaneId);

     void markAsNotNeeded(final UUID sourceLaneId, final String scope, final Agent agent);
}
