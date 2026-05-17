package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component("qaLeadAgentExecutor")
public class QaLeadAgentExecutor implements ExecuteAgent<QaLeadPayload> {

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        log.info("Execute qa_lead lane: " + lane.getLaneId());
    }
}
