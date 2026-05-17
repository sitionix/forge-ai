package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUiPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component("testUiAgentExecutor")
public class TestUiAgentExecutor implements ExecuteAgent<TestUiPayload> {

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        log.info("Execute test_ui lane: " + lane.getLaneId());
    }
}
