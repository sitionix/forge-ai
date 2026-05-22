package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.domain.usecase.CompleteAgentLane;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ReviewerPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component("reviewerAgentExecutor")
public class ReviewerAgentExecutor implements ExecuteAgent<ReviewerPayload> {

    private final CompleteAgentLane completeAgentLane;

    public ReviewerAgentExecutor(final CompleteAgentLane completeAgentLane) {
        this.completeAgentLane = completeAgentLane;
    }

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        log.info("Execute reviewer lane: " + lane.getLaneId());
        this.completeAgentLane.completeAndPrepareAgents(lane.getLaneId());
    }
}
