package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component("testUnitAgentExecutor")
public class TestUnitAgentExecutor implements ExecuteAgent<AgentTicketPayload> {

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        log.info("Execute test_unit lane: " + lane.getLaneId());
    }

    @Override
    public void executeTicket(final AgentTicket<AgentTicketPayload> ticket) {
        log.info("Execute test_unit ticket: " + ticket.getId());
    }
}
