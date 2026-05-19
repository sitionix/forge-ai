package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneDependency;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.experimental.UtilityClass;

@UtilityClass
public class LaneTaskResolver {

    public Set<AgentTicketPayload> resolve(final Lane laneState,
                                           final AgentTicketRepository agentTicketRepository,
                                           final Class<? extends AgentTicketPayload> payloadType) {
        if (Objects.isNull(laneState.getInputTaskIds()) || laneState.getInputTaskIds().isEmpty()) {
            throw new IllegalStateException("No input task ids found for laneId=" + laneState.getId());
        }
        final List<AgentTicket<AgentTicketPayload>> inputTickets = new ArrayList<>();
        for (final var inputTaskId : laneState.getInputTaskIds()) {
            final AgentTicket<? extends AgentTicketPayload> ticket = agentTicketRepository.findById(inputTaskId, payloadType)
                    .orElseThrow(() -> new IllegalArgumentException("Agent ticket not found with id: " + inputTaskId));
            inputTickets.add((AgentTicket<AgentTicketPayload>) ticket);
        }
        if (Objects.isNull(laneState.getDependsOn()) || laneState.getDependsOn().isEmpty()) {
            return inputTickets.stream()
                .map(AgentTicket::getPayload)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        }

        final Set<AgentTicketPayload> result = new LinkedHashSet<>();
        for (final LaneDependency dependency : laneState.getDependsOn()) {
            final String scope = dependency.getScope();
            final Set<AgentTicketPayload> byScope = inputTickets.stream()
                    .filter(ticket -> Objects.equals(ticket.getAgent(), dependency.getType()))
                    .filter(ticket -> Objects.equals(ticket.getScope(), scope))
                    .map(AgentTicket::getPayload)
                    .collect(LinkedHashSet::new, Set::add, Set::addAll);
            if (!byScope.isEmpty()) {
                result.addAll(byScope);
                continue;
            }
            final Set<AgentTicketPayload> byGlobal = inputTickets.stream()
                    .filter(ticket -> Objects.equals(ticket.getAgent(), dependency.getType()))
                    .filter(ticket -> Objects.equals(ticket.getScope(), ScopeMode.GLOBAL_SCOPE))
                    .map(AgentTicket::getPayload)
                    .collect(LinkedHashSet::new, Set::add, Set::addAll);
            if (!byGlobal.isEmpty()) {
                result.addAll(byGlobal);
                continue;
            }
            throw new IllegalStateException("No input task found for laneId=" + laneState.getId()
                    + ", dependencyAgent=" + dependency.getType()
                    + ", dependencyScope=" + dependency.getScope());
        }
        return result;
    }
}
