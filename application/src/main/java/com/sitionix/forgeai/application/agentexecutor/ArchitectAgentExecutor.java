package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component("architectAgentExecutor")
public class ArchitectAgentExecutor implements ExecuteAgent<ArchitectPayload> {

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        log.info("Execute architect lane: " + lane.getLaneId());
    }
}
