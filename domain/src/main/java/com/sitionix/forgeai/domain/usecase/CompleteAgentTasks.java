package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import java.util.Collection;
import java.util.UUID;

/**
 * Completes a source lane by creating one or more downstream agent tasks.
 */
public interface CompleteAgentTasks {

    void complete(final UUID sourceLaneId, final Collection<? extends AgentTicket<? extends AgentTicketPayload>> agentTickets);
}
