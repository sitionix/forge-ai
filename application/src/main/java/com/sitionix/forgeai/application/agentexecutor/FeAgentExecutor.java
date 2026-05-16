package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component("feAgentExecutor")
public class FeAgentExecutor implements ExecuteAgent {

    @Override
    public void execute(final ReadyToStartLane lane) {
        log.info("Execute implement_fe lane: " + lane.getLaneId());
    }
}
