package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import java.util.Map;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component("feAgentExecutor")
public class FeAgentExecutor extends SupervisedTaskDrivenAgentExecutor implements ExecuteAgent<ImplementFePayload> {

    public FeAgentExecutor(final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase,
                           final AgentTicketRepository agentTicketRepository,
                           final TicketRepository ticketRepository,
                           final SupervisedLaneExecutionUseCase supervisedLaneExecutionUseCase,
                           final SupervisedExecutionProperties supervisedExecutionProperties,
                           final LaneCompletionSupport laneCompletionSupport,
                           final CompleteAgentTasks completeAgentTasks) {
        super(
                prepareAgentExecutionInputUseCase,
                agentTicketRepository,
                ticketRepository,
                supervisedLaneExecutionUseCase,
                supervisedExecutionProperties,
                laneCompletionSupport,
                completeAgentTasks
        );
    }

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        log.info("Execute implement_fe lane: " + lane.getLaneId());
        this.executeWithSupervisor(lane);
    }

    @Override
    public void validateFinalCompletionPayload(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        this.laneCompletionSupport.validateProducedLaneInputs(lane, completionPayload);
    }

    @Override
    public void completeLane(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        this.laneCompletionSupport.completeProducedLaneInputs(lane, completionPayload);
    }
}
