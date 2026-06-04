package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ReviewerPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import com.sitionix.forgeai.domain.usecase.CompleteReviewerTask;
import java.util.Map;
import java.util.Objects;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component("reviewerAgentExecutor")
public class ReviewerAgentExecutor extends SupervisedTaskDrivenAgentExecutor implements ExecuteAgent<ReviewerPayload> {

    private final CompleteReviewerTask completeReviewerTask;

    public ReviewerAgentExecutor(final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase,
                                 final AgentTicketRepository agentTicketRepository,
                                 final TicketRepository ticketRepository,
                                 final SupervisedLaneExecutionUseCase supervisedLaneExecutionUseCase,
                                 final SupervisedExecutionProperties supervisedExecutionProperties,
                                 final LaneCompletionSupport laneCompletionSupport,
                                 final CompleteAgentTasks completeAgentTasks,
                                 final CompleteReviewerTask completeReviewerTask) {
        super(
                prepareAgentExecutionInputUseCase,
                agentTicketRepository,
                ticketRepository,
                supervisedLaneExecutionUseCase,
                supervisedExecutionProperties,
                laneCompletionSupport,
                completeAgentTasks
        );
        this.completeReviewerTask = completeReviewerTask;
    }

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        log.info("Execute reviewer lane: " + lane.getLaneId());
        this.executeWithSupervisor(lane);
    }

    @Override
    public void validateFinalCompletionPayload(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        final Object scope = completionPayload.get("scope");
        if (scope != null) {
            this.laneCompletionSupport.requireExpectedScope(lane, Objects.toString(scope, null));
        }
    }

    @Override
    public void completeLane(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        this.completeReviewerTask.complete(lane.getTicketId());
    }
}
