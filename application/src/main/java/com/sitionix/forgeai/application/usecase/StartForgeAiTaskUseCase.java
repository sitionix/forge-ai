package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.TicketStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneDependency;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.port.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.port.TicketRepository;
import com.sitionix.forgeai.domain.usecase.StartForgeAiTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StartForgeAiTaskUseCase implements StartForgeAiTask {

    private final TicketRepository ticketRepository;

    private final ServicePropertiesProvider props;

    @Override
    public Ticket execute(final ForgeAiStartCommand command) {
        final List<ServicePropertiesProvider.ServiceConfigView> laneProps = command.getServiceIds().stream()
                .map(serviceId -> props.getServices().get(serviceId))
                .toList();

        final Ticket ticket = Ticket.builder()
                .id(UUID.randomUUID())
                .createdAt(LocalDateTime.now())
                .taskDescription(command.getTask())
                .status(TicketStatus.OPEN)
                .ticketKey(command.getTicket())
                .lanes(this.mapLane(laneProps))
                .build();

        return ticketRepository.save(ticket);
    }

    private List<Lane> mapLane(final List<ServicePropertiesProvider.ServiceConfigView> services) {
        final List<String> selectedScopes = services.stream()
                .map(ServicePropertiesProvider.ServiceConfigView::getPath)
                .toList();
        return Arrays.stream(Agent.values())
                .flatMap(agent -> agent.getInfo()
                        .getScopeMode()
                        .laneScopes(selectedScopes)
                        .stream()
                        .filter(scope -> this.isAgentAllowedForScope(agent, scope, services))
                        .map(scope -> this.buildLane(agent, scope, selectedScopes, services)))
                .toList();
    }

    private LinkedHashSet<LaneDependency> resolveDependencies(
            final Agent agent,
            final String currentScope,
            final List<String> selectedScopes,
            final List<ServicePropertiesProvider.ServiceConfigView> services
    ) {
        return agent.getInfo().getDependsOn().stream()
                .flatMap(dep -> dep.getInfo()
                        .getScopeMode()
                        .dependencyScopes(selectedScopes, currentScope)
                        .stream()
                        .filter(scope -> this.isAgentAllowedForScope(dep, scope, services))
                        .map(scope -> this.dependency(dep, scope)))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private LaneDependency dependency(final Agent agent, final String scope) {
        return LaneDependency.builder()
                .type(agent)
                .scope(scope)
                .build();
    }

    private Lane buildLane(
            final Agent agent,
            final String scope,
            final List<String> selectedScopes,
            final List<ServicePropertiesProvider.ServiceConfigView> services
    ) {
        return Lane.builder()
                .id(UUID.randomUUID())
                .agent(agent)
                .scope(scope)
                .status(this.resolveLaneStatus(agent))
                .attempt(0)
                .dependsOn(this.resolveDependencies(agent, scope, selectedScopes, services))
                .build();
    }

    private boolean isAgentAllowedForScope(
            final Agent agent,
            final String scope,
            final List<ServicePropertiesProvider.ServiceConfigView> services
    ) {
        if (ScopeMode.GLOBAL_SCOPE.equals(scope)) {
            return services.stream()
                    .anyMatch(service -> agent.getInfo().getGroups().contains(service.getGroup()));
        }

        return services.stream()
                .filter(service -> service.getPath().equals(scope))
                .anyMatch(service -> agent.getInfo().getGroups().contains(service.getGroup()));
    }

    private LaneStatus resolveLaneStatus(final Agent agent) {
        if (agent.equals(Agent.ANALYZER)) {
            return LaneStatus.READY_TO_START;
        }
        return LaneStatus.NOT_STARTED;
    }
}
