package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneDependency;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import java.util.LinkedHashSet;
import java.util.List;
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
        final List<AgentTicket<AgentTicketPayload>> inputTickets = laneState.getInputTaskIds().stream()
                .map(inputTaskId -> agentTicketRepository.findById(inputTaskId, payloadType)
                        .orElseThrow(() -> new IllegalArgumentException("Agent ticket not found with id: " + inputTaskId)))
                .map(ticket -> (AgentTicket<AgentTicketPayload>) ticket)
                .toList();
        if (Objects.isNull(laneState.getDependsOn()) || laneState.getDependsOn().isEmpty()) {
            return inputTickets.stream()
                .map(AgentTicket::getPayload)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        }

        return laneState.getDependsOn().stream()
                .map(dependency -> resolveByDependency(laneState, inputTickets, dependency))
                .flatMap(Set::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<AgentTicketPayload> resolveByDependency(final Lane laneState,
                                                        final List<AgentTicket<AgentTicketPayload>> inputTickets,
                                                        final LaneDependency dependency) {
        final Set<AgentTicketPayload> byScope = inputTickets.stream()
                .filter(ticket -> Objects.equals(ticket.getScope(), dependency.getScope()))
                .map(AgentTicket::getPayload)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!byScope.isEmpty()) {
            return byScope;
        }
        final Set<AgentTicketPayload> byGlobal = inputTickets.stream()
                .filter(ticket -> Objects.equals(ticket.getScope(), ScopeMode.GLOBAL_SCOPE))
                .map(AgentTicket::getPayload)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!byGlobal.isEmpty()) {
            return byGlobal;
        }
        throw new IllegalStateException("No input task found for laneId=" + laneState.getId()
                + ", dependencyAgent=" + dependency.getType()
                + ", dependencyScope=" + dependency.getScope());
    }
}
