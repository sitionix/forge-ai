package com.sitionix.forgeai.domain.model.ticket.lane;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;

public interface ExecuteAgent<P extends AgentTicketPayload> {

     void executeLane(final ReadyToStartLane lane);

     void executeTicket(final AgentTicket<P> ticket);
}
