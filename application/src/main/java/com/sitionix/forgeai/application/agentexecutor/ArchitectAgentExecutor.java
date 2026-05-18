package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@RequiredArgsConstructor
@Component("architectAgentExecutor")
public class ArchitectAgentExecutor implements ExecuteAgent<ArchitectPayload> {


    private final PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase;
    private final CodexClient codexClient;
    private final AgentTicketRepository agentTicketRepository;
    private final TicketRepository ticketRepository;

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        log.info("Execute architect lane: " + lane.getLaneId());

        final AgentExecutionInput<AgentTicketPayload> input = this.prepareAgentExecutionInputUseCase.execute(lane);
        final Lane laneState = this.ticketRepository.findByLaneId(lane.getLaneId())
                .orElseThrow(() -> new IllegalArgumentException("Lane not found with id: " + lane.getLaneId()));
        final UUID inputTaskId = laneState.singleInputTaskIdForExecution();

        final AgentTicket<ArchitectPayload> agentTicket = this.agentTicketRepository.findById(inputTaskId, ArchitectPayload.class)
                .orElseThrow(() -> new IllegalArgumentException("Agent ticket not found with id: " + inputTaskId));

        final AgentExecutionInput<AgentTicketPayload> enrichedInput = this.prepareAgentExecutionInputUseCase.enrichWithPayload(
                lane,
                input,
                agentTicket.getPayload()
        );
        this.codexClient.submit(enrichedInput, lane.getSourceTerminalTty());
    }
}
