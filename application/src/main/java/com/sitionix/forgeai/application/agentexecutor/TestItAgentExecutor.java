package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItCompletionPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentLane;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component("testItAgentExecutor")
public class TestItAgentExecutor extends SupervisedTaskDrivenAgentExecutor implements ExecuteAgent<TestItPayload> {

    private final CompleteAgentLane completeAgentLane;

    public TestItAgentExecutor(final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase,
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
        log.info("Execute test_it lane: " + lane.getLaneId());
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
        this.laneCompletionSupport.requireExpectedScope(lane, this.laneCompletionSupport.requireText(completionPayload, "scope"));
        final TestItCompletionPayload payload = this.laneCompletionSupport.convert(completionPayload, TestItCompletionPayload.class);
        final AgentTicket<TestItCompletionPayload> completionReport = AgentTicket.<TestItCompletionPayload>builder()
                .id(UUID.randomUUID())
                .ticketId(lane.getTicketId())
                .laneId(lane.getLaneId())
                .status(AgentTicketStatus.CONSUMED)
                .scope(lane.getScope())
                .agent(Agent.TEST_IT)
                .payload(payload)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        this.agentTicketRepository.save(completionReport);
        this.completeAgentLane.completeAndPrepareAgents(lane.getLaneId());
    }
}
