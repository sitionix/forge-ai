package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

abstract class TaskDrivenCodexAgentExecutor {

    protected final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase;
    protected final CodexClient codexClient;
    protected final AgentTicketRepository agentTicketRepository;
    protected final TicketRepository ticketRepository;

    protected TaskDrivenCodexAgentExecutor(final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase,
                                           final CodexClient codexClient,
                                           final AgentTicketRepository agentTicketRepository,
                                           final TicketRepository ticketRepository) {
        this.prepareAgentExecutionInputUseCase = prepareAgentExecutionInputUseCase;
        this.codexClient = codexClient;
        this.agentTicketRepository = agentTicketRepository;
        this.ticketRepository = ticketRepository;
    }

    protected void executeWithTasks(final ReadyToStartLane lane) {
        final AgentExecutionInput<AgentTicketPayload> enrichedInput = this.prepareInputWithTasks(lane);
        this.codexClient.submit(enrichedInput, lane.getSourceTerminalTty());
    }

    protected AgentExecutionInput<AgentTicketPayload> prepareInputWithTasks(final ReadyToStartLane lane) {
        final AgentExecutionInput<AgentTicketPayload> input = this.prepareAgentExecutionInputUseCase.execute(lane);
        final Lane laneState = this.ticketRepository.findByLaneId(lane.getLaneId())
                .orElseThrow(() -> new IllegalArgumentException("Lane not found with id: " + lane.getLaneId()));
        final Set<AgentTicketPayload> tasks = LaneTaskResolver.resolve(laneState, this.agentTicketRepository);
        return this.prepareAgentExecutionInputUseCase.enrichWithTasks(
                lane,
                input,
                tasks
        );
    }

    protected AgentExecutionInput<AgentTicketPayload> prepareInputWithOptionalTasks(final ReadyToStartLane lane) {
        final AgentExecutionInput<AgentTicketPayload> input = this.prepareAgentExecutionInputUseCase.execute(lane);
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
