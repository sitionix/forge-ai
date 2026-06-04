package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.laneexecution.SupervisedExecutionProperties;
import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.application.usecase.SupervisedLaneExecutionUseCase;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;

abstract class SupervisedTaskDrivenAgentExecutor {

    protected final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase;
    protected final AgentTicketRepository agentTicketRepository;
    protected final TicketRepository ticketRepository;
    protected final SupervisedLaneExecutionUseCase supervisedLaneExecutionUseCase;
    protected final SupervisedExecutionProperties supervisedExecutionProperties;
    protected final LaneCompletionSupport laneCompletionSupport;
    protected final CompleteAgentTasks completeAgentTasks;

    protected SupervisedTaskDrivenAgentExecutor(final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase,
                                                final AgentTicketRepository agentTicketRepository,
                                                final TicketRepository ticketRepository,
                                                final SupervisedLaneExecutionUseCase supervisedLaneExecutionUseCase,
                                                final SupervisedExecutionProperties supervisedExecutionProperties,
                                                final LaneCompletionSupport laneCompletionSupport,
                                                final CompleteAgentTasks completeAgentTasks) {
        this.prepareAgentExecutionInputUseCase = prepareAgentExecutionInputUseCase;
        this.agentTicketRepository = agentTicketRepository;
        this.ticketRepository = ticketRepository;
        this.supervisedLaneExecutionUseCase = supervisedLaneExecutionUseCase;
        this.supervisedExecutionProperties = supervisedExecutionProperties;
        this.laneCompletionSupport = laneCompletionSupport;
        this.completeAgentTasks = completeAgentTasks;
    }

    protected void executeWithSupervisor(final ReadyToStartLane lane) {
        final AgentExecutionInput<AgentTicketPayload> enrichedInput = this.prepareInputWithOptionalTasks(lane);
        this.supervisedLaneExecutionUseCase.execute(lane, enrichedInput, this.supervisedExecutionProperties.getCorrectionAttempts());
    }

    protected AgentExecutionInput<AgentTicketPayload> prepareInputWithOptionalTasks(final ReadyToStartLane lane) {
        final AgentExecutionInput<AgentTicketPayload> input = this.prepareAgentExecutionInputUseCase.executeClaimed(lane);
        final Lane laneState = this.ticketRepository.findByLaneId(lane.getLaneId())
                .orElseThrow(() -> new IllegalArgumentException("Lane not found with id: " + lane.getLaneId()));
        final Set<AgentTicketPayload> tasks;
        if (Objects.isNull(laneState.getInputTaskIds()) || laneState.getInputTaskIds().isEmpty()) {
            tasks = new LinkedHashSet<>();
        } else {
            tasks = LaneTaskResolver.resolve(laneState, this.agentTicketRepository);
        }
        return this.prepareAgentExecutionInputUseCase.enrichWithTasks(lane, input, tasks);
    }
}
