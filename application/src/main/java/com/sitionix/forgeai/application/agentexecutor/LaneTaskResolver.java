package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneDependency;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.experimental.UtilityClass;

@UtilityClass
public class LaneTaskResolver {

    public Set<AgentTicketPayload> resolve(final Lane laneState,
                                           final AgentTicketRepository agentTicketRepository,
                                           final Class<? extends AgentTicketPayload> payloadType) {
        if (Objects.isNull(laneState.getInputTaskIds()) || laneState.getInputTaskIds().isEmpty()) {
            throw new IllegalStateException("No input task ids found for laneId=" + laneState.getId());
        }
        final List<AgentTicket<AgentTicketPayload>> inputTickets = laneState.getInputTaskIds().stream()
                .map(inputTaskId -> getTicketById(agentTicketRepository, inputTaskId, payloadType))
                .toList();
        if (Objects.isNull(laneState.getDependsOn()) || laneState.getDependsOn().isEmpty()) {
            return inputTickets.stream()
                    .map(AgentTicket::getPayload)
                    .collect(LinkedHashSet::new, Set::add, Set::addAll);
        }

        final Set<AgentTicketPayload> result = new LinkedHashSet<>();
        for (final LaneDependency dependency : laneState.getDependsOn()) {
            final List<AgentTicket<AgentTicketPayload>> byScope = findByAgentAndScope(inputTickets, dependency.getType(), dependency.getScope());
            if (!byScope.isEmpty()) {
                byScope.stream()
                        .map(AgentTicket::getPayload)
                        .forEach(result::add);
                continue;
            }
            final List<AgentTicket<AgentTicketPayload>> byGlobalScope = findByAgentAndScope(inputTickets, dependency.getType(), ScopeMode.GLOBAL_SCOPE);
            if (!byGlobalScope.isEmpty()) {
                byGlobalScope.stream()
                        .map(AgentTicket::getPayload)
                        .forEach(result::add);
                continue;
            }
            throw new IllegalStateException("No input task found for laneId=" + laneState.getId()
                    + ", dependencyAgent=" + dependency.getType()
                    + ", dependencyScope=" + dependency.getScope());
        }
        return result;
    }

    private AgentTicket<AgentTicketPayload> getTicketById(final AgentTicketRepository agentTicketRepository,
                                                          final UUID inputTaskId,
                                                          final Class<? extends AgentTicketPayload> payloadType) {
        return agentTicketRepository.findById(inputTaskId, payloadType)
                .map(value -> (AgentTicket<AgentTicketPayload>) value)
                .orElseThrow(() -> new IllegalArgumentException("Agent ticket not found with id: " + inputTaskId));
    }

    private List<AgentTicket<AgentTicketPayload>> findByAgentAndScope(final List<AgentTicket<AgentTicketPayload>> tickets,
                                                                       final Agent agent,
                                                                       final String scope) {
        final List<AgentTicket<AgentTicketPayload>> result = new ArrayList<>();
        for (final AgentTicket<AgentTicketPayload> ticket : tickets) {
            if (Objects.equals(ticket.getAgent(), agent) && Objects.equals(ticket.getScope(), scope)) {
                result.add(ticket);
            }
        }
        return result;
    }
}
