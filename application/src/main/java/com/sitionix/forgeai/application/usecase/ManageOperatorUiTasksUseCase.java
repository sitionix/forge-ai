package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.usecase.ManageOperatorUiTasks;
import com.sitionix.forgeai.domain.usecase.StartForgeAiTask;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManageOperatorUiTasksUseCase implements ManageOperatorUiTasks {

    private final ServicePropertiesProvider servicePropertiesProvider;
    private final StartForgeAiTask startForgeAiTask;

    @Override
    public OperatorUiServiceCatalogResponse services() {
        final Map<String, ServicePropertiesProvider.ServiceConfigView> services = this.servicePropertiesProvider.getServices();
        if (services == null || services.isEmpty()) {
            return new OperatorUiServiceCatalogResponse(List.of());
        }
        return new OperatorUiServiceCatalogResponse(services.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(this::serviceOption)
                .sorted(Comparator
                        .comparing(OperatorUiServiceOption::group, Comparator.nullsLast(String::compareTo))
                        .thenComparing(OperatorUiServiceOption::label, Comparator.nullsLast(String::compareTo))
                        .thenComparing(OperatorUiServiceOption::id))
                .toList());
    }

    @Override
    public OperatorUiTaskMutationResponse create(final OperatorUiCreateTaskCommand command) {
        return this.response(this.startForgeAiTask.createOpen(this.command(command)));
    }

    @Override
    public OperatorUiTaskMutationResponse execute(final UUID ticketId) {
        if (ticketId == null) {
            throw new IllegalArgumentException("Ticket id is required");
        }
        return this.response(this.startForgeAiTask.executeOpen(ticketId));
    }

    private OperatorUiServiceOption serviceOption(final Map.Entry<String, ServicePropertiesProvider.ServiceConfigView> entry) {
        final ServicePropertiesProvider.ServiceConfigView service = entry.getValue();
        return new OperatorUiServiceOption(
                entry.getKey(),
                Objects.toString(service.getLabel(), entry.getKey()),
                service.getPath(),
                service.getGroup() == null ? null : service.getGroup().name(),
                service.getTags() == null ? List.of() : service.getTags()
        );
    }

    private ForgeAiStartCommand command(final OperatorUiCreateTaskCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Create task command is required");
        }
        return ForgeAiStartCommand.builder()
                .ticket(command.ticket())
                .task(command.task())
                .serviceIds(command.serviceIds())
                .sourceTerminalTty(command.sourceTerminalTty())
                .build();
    }

    private OperatorUiTaskMutationResponse response(final Ticket ticket) {
        return new OperatorUiTaskMutationResponse(
                ticket.getId(),
                ticket.getTicketKey(),
                ticket.getStatus() == null ? null : ticket.getStatus().name(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt()
        );
    }
}
