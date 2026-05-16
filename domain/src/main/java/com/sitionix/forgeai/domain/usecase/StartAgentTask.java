package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;

@FunctionalInterface
public interface StartAgentTask {

     <P extends AgentTicketPayload> void execute(final AgentTicket<P> agentTicket);
}
