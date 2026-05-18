package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentLane;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
@Service
@Log
public class CreateAgentTaskUseCase implements CreateAgentTask {

    private final AgentTicketRepository agentTicketRepository;

    private final LaneRepository laneRepository;
    private final TicketRepository ticketRepository;

    private final CompleteAgentLane completeAgentLane;

    @Override
    public <P extends AgentTicketPayload> void create(final AgentTicket<P> agentTicket, final UUID sourceLaneId) {
        final Lane laneToProduce = this.findLaneToProduceOptional(sourceLaneId, agentTicket.getScope(), agentTicket.getAgent())
                .orElseThrow(() -> this.laneNotFound(sourceLaneId, agentTicket.getScope(), agentTicket.getAgent()));
        this.completeInfo(agentTicket, laneToProduce);

        this.agentTicketRepository.save(agentTicket);
        log.info("Created agent ticket: " + agentTicket.getId());

        this.laneRepository.assignInputTaskId(laneToProduce.getId(), agentTicket.getId());
        if (this.ticketRepository.isReadyToStart(laneToProduce.getId())) {
            this.ticketRepository.updateLaneStatus(laneToProduce.getId(), LaneStatus.READY_TO_START);
        }

        this.completeAgentLane.completeAndPrepareAgents(sourceLaneId);
    }

    private void completeInfo(final AgentTicket<?> agentTicket, final Lane lane) {
        agentTicket.setCreatedAt(LocalDateTime.now());
        agentTicket.setUpdatedAt(LocalDateTime.now());
        agentTicket.setLaneId(lane.getId());
    }

    private Optional<Lane> findLaneToProduceOptional(final UUID sourceLaneId, final String scope, final Agent agent) {
        return this.laneRepository.findLaneToProduceOptional(sourceLaneId, scope, agent);
    }

    @Override
    public void markAsNotNeeded(final UUID sourceLaneId, final String scope, final Agent agent) {
        final Lane laneToProduce = this.findLaneToProduceOptional(sourceLaneId, scope, agent)
                .orElseThrow(() -> this.laneNotFound(sourceLaneId, scope, agent));
        this.ticketRepository.updateLaneStatus(laneToProduce.getId(), LaneStatus.NOT_NEEDED);
        this.completeAgentLane.completeAndPrepareAgents(sourceLaneId);
    }

    private IllegalStateException laneNotFound(final UUID sourceLaneId, final String scope, final Agent agent) {
        return new IllegalStateException("Produced lane not found for sourceLaneId=" + sourceLaneId
                + ", scope=" + scope
                + ", agent=" + agent);
    }
}
