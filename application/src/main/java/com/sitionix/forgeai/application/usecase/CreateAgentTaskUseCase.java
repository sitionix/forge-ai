package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentLane;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@RequiredArgsConstructor
@Service
@Log
public class CreateAgentTaskUseCase implements CreateAgentTask {

    private final AgentTicketRepository agentTicketRepository;

    private final LaneRepository laneRepository;

    private final CompleteAgentLane completeAgentLane;

    @Override
    public <P extends AgentTicketPayload> void create(final AgentTicket<P> agentTicket, final UUID sourceLaneId) {

        final Lane laneToProduce = this.findLaneToProduce(sourceLaneId, agentTicket.getPayload().getScope(), agentTicket.getPayload().getAgent() );
        this.completeInfo(agentTicket, laneToProduce);

        final AgentTicket<P> created = this.agentTicketRepository.save(agentTicket);
        log.info("Created agent ticket: " + agentTicket.getId());

        this.laneRepository.assignInputTaskId(laneToProduce.getId(), created.getId());

        this.completeAgentLane.completeAndPrepareAgents(sourceLaneId);
    }

    private void completeInfo(final AgentTicket<?> agentTicket, final Lane lane) {
        agentTicket.setCreatedAt(LocalDateTime.now());
        agentTicket.setUpdatedAt(LocalDateTime.now());
        agentTicket.setLaneId(lane.getId());
    }

    private Lane findLaneToProduce(final UUID sourceLaneId, final String scope, final Agent agent) {
        return this.laneRepository.findLaneToProduce(sourceLaneId, scope, agent);
    }
}
