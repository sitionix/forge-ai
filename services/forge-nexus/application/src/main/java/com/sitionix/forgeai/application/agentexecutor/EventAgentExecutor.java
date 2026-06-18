package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentLane;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import java.util.Map;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component("eventAgentExecutor")
public class EventAgentExecutor extends SupervisedTaskDrivenAgentExecutor implements ExecuteAgent<EventPayload> {

    private final CompleteAgentLane completeAgentLane;

    public EventAgentExecutor(final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase,
                              final AgentTicketRepository agentTicketRepository,
                              final TicketRepository ticketRepository,
                              final SupervisedLaneExecutionUseCase supervisedLaneExecutionUseCase,
                              final SupervisedExecutionProperties supervisedExecutionProperties,
                              final LaneCompletionSupport laneCompletionSupport,
                              final CompleteAgentTasks completeAgentTasks,
                              final CompleteAgentLane completeAgentLane) {
        super(
                prepareAgentExecutionInputUseCase,
                agentTicketRepository,
                ticketRepository,
                supervisedLaneExecutionUseCase,
                supervisedExecutionProperties,
                laneCompletionSupport,
                completeAgentTasks
        );
        this.completeAgentLane = completeAgentLane;
    }

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        log.info("Execute event lane: " + lane.getLaneId());
        this.executeWithSupervisor(lane);
    }

    @Override
    public void validateFinalCompletionPayload(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        this.laneCompletionSupport.validateNoOutputs(completionPayload);
    }

    @Override
    public void completeLane(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        this.completeAgentLane.completeAndPrepareAgents(lane.getLaneId());
    }
}
