package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

@UtilityClass
public class LaneTaskResolver {

    public Set<AgentTicketPayload> resolve(final Lane laneState,
                                           final AgentTicketRepository agentTicketRepository,
                                           final Class<? extends AgentTicketPayload> payloadType) {
        if (Objects.isNull(laneState.getInputTaskIds()) || laneState.getInputTaskIds().isEmpty()) {
            throw new IllegalStateException("No input task ids found for laneId=" + laneState.getId());
        }
        return laneState.getInputTaskIds().stream()
                .map(inputTaskId -> agentTicketRepository.findById(inputTaskId, payloadType)
                        .orElseThrow(() -> new IllegalArgumentException("Agent ticket not found with id: " + inputTaskId)))
                .map(ticket -> (AgentTicket<AgentTicketPayload>) ticket)
                .map(AgentTicket::getPayload)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
