package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.application.operator.TicketOperatorRunService;
import com.sitionix.forgeai.application.operator.TicketOperatorTerminalAutoOpenService;
import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.TicketStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneDependency;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.StartForgeAiTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StartForgeAiTaskUseCase implements StartForgeAiTask {

    private final TicketRepository ticketRepository;

    private final ServicePropertiesProvider props;

    private final TicketOperatorRunService ticketOperatorRunService;
    private final TicketOperatorTerminalAutoOpenService ticketOperatorTerminalAutoOpenService;

    @Override
    public Ticket execute(final ForgeAiStartCommand command) {
        return this.create(command, TicketStatus.READY_TO_START, true);
    }

    @Override
    public Ticket createOpen(final ForgeAiStartCommand command) {
        return this.create(command, TicketStatus.OPEN, false);
    }

    @Override
    public Ticket executeOpen(final UUID ticketId) {
        final Ticket ticket = this.ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketId));
        if (TicketStatus.READY_TO_START.equals(ticket.getStatus())) {
            return ticket;
        }
        if (!TicketStatus.OPEN.equals(ticket.getStatus())) {
            throw new IllegalStateException("Only OPEN ticket can be executed: ticketId=" + ticketId + ", status=" + ticket.getStatus());
        }
        ticket.setStatus(TicketStatus.READY_TO_START);
        ticket.setUpdatedAt(LocalDateTime.now());
        final Ticket saved = this.ticketRepository.save(ticket);
        this.ticketOperatorRunService.publishEvent(this.ticketOperatorRunService.ticketEvent(
                saved.getId(),
                saved.getTicketKey(),
                "TICKET_READY_TO_START",
                "Ticket moved to READY_TO_START"
        ));
        return saved;
    }

    private Ticket create(final ForgeAiStartCommand command, final TicketStatus initialStatus, final boolean autoOpenTerminal) {
        final List<SelectedService> laneProps = this.selectedServices(command);

        final Ticket ticket = Ticket.builder()
                .id(UUID.randomUUID())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .taskDescription(command.getTask())
                .sourceTerminalTty(command.getSourceTerminalTty())
                .status(initialStatus)
                .ticketKey(command.getTicket())
                .lanes(this.mapLane(laneProps))
                .build();

        final Ticket saved = ticketRepository.save(ticket);
        if (autoOpenTerminal) {
            this.ticketOperatorRunService.initializeRun(saved);
            this.ticketOperatorTerminalAutoOpenService.openIfConfigured(saved);
        }
        return saved;
    }

    private List<SelectedService> selectedServices(final ForgeAiStartCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Start command is required");
        }
        if (command.getTicket() == null || command.getTicket().isBlank()) {
            throw new IllegalArgumentException("Ticket key is required");
        }
        if (command.getTask() == null || command.getTask().isBlank()) {
            throw new IllegalArgumentException("Task description is required");
        }
        if (command.getServiceIds() == null || command.getServiceIds().isEmpty()) {
            throw new IllegalArgumentException("At least one service id is required");
        }
        final Map<String, ServicePropertiesProvider.ServiceConfigView> services = this.props.getServices();
        return command.getServiceIds().stream()
                .distinct()
                .map(serviceId -> new SelectedService(serviceId, this.service(services, serviceId)))
                .toList();
    }

    private ServicePropertiesProvider.ServiceConfigView service(
            final Map<String, ServicePropertiesProvider.ServiceConfigView> services,
            final String serviceId
    ) {
        if (services == null || !services.containsKey(serviceId) || services.get(serviceId) == null) {
            throw new IllegalArgumentException("Unknown service id: " + serviceId);
        }
        return services.get(serviceId);
    }

    private List<Lane> mapLane(final List<SelectedService> services) {
        final List<String> selectedScopes = services.stream()
                .map(value -> value.getService().getPath())
                .toList();
        return Arrays.stream(Agent.values())
                .filter(agent -> agent.getInfo().isEnabled())
                .flatMap(agent -> agent.getInfo()
                        .getScopeMode()
                        .laneScopes(selectedScopes)
                        .stream()
                        .filter(scope -> this.isAgentAllowedForScope(agent, scope, services))
                        .map(scope -> this.buildLane(agent, scope, selectedScopes, services, this.resolveServiceId(scope, services))))
                .toList();
    }

    private LinkedHashSet<LaneDependency> resolveDependencies(
            final Agent agent,
            final String currentScope,
            final List<String> selectedScopes,
            final List<SelectedService> services
    ) {
        return agent.getInfo().getDependsOn().stream()
                .filter(dep -> dep.getInfo().isEnabled())
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
            final List<SelectedService> services,
            final String serviceId
    ) {
        return Lane.builder()
                .id(UUID.randomUUID())
                .agent(agent)
                .scope(scope)
                .serviceId(serviceId)
                .status(this.resolveLaneStatus(agent))
                .attempt(0)
                .dependsOn(this.resolveDependencies(agent, scope, selectedScopes, services))
                .build();
    }

    private boolean isAgentAllowedForScope(
            final Agent agent,
            final String scope,
            final List<SelectedService> services
    ) {
        if (ScopeMode.GLOBAL_SCOPE.equals(scope)) {
            return services.stream()
                    .anyMatch(service -> agent.getInfo().getGroups().contains(service.getService().getGroup()));
        }

        return services.stream()
                .filter(service -> service.getService().getPath().equals(scope))
                .anyMatch(service -> agent.getInfo().getGroups().contains(service.getService().getGroup()));
    }

    private String resolveServiceId(final String scope, final List<SelectedService> services) {
        if (ScopeMode.GLOBAL_SCOPE.equals(scope)) {
            return "global";
        }
        return services.stream()
                .filter(service -> service.getService().getPath().equals(scope))
                .findFirst()
                .map(SelectedService::getServiceId)
                .orElseThrow(() -> new IllegalArgumentException("Service id not found for scope: " + scope));
    }

    private LaneStatus resolveLaneStatus(final Agent agent) {
        if (agent.equals(Agent.ANALYZER)) {
            return LaneStatus.READY_TO_START;
        }
        return LaneStatus.NOT_STARTED;
    }

    private static class SelectedService {
        private final String serviceId;
        private final ServicePropertiesProvider.ServiceConfigView service;

        private SelectedService(final String serviceId, final ServicePropertiesProvider.ServiceConfigView service) {
            this.serviceId = serviceId;
            this.service = service;
        }

        public String getServiceId() {
            return this.serviceId;
        }

        public ServicePropertiesProvider.ServiceConfigView getService() {
            return this.service;
        }
    }
}
