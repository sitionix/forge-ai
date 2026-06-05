package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.TicketStatus;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.usecase.ManageOperatorUiTasks;
import com.sitionix.forgeai.domain.usecase.ManageOperatorUiTasks.OperatorUiCreateTaskCommand;
import com.sitionix.forgeai.domain.usecase.StartForgeAiTask;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class ManageOperatorUiTasksUseCaseTest {

    private ManageOperatorUiTasks manageOperatorUiTasks;

    @Mock
    private ServicePropertiesProvider servicePropertiesProvider;
    @Mock
    private StartForgeAiTask startForgeAiTask;

    @BeforeEach
    void setUp() {
        this.manageOperatorUiTasks = new ManageOperatorUiTasksUseCase(this.servicePropertiesProvider, this.startForgeAiTask);
    }

    @Test
    void givenServicesConfigured_whenServices_thenReturnCatalogFromYamlProvider() {
        final ServicePropertiesProvider.ServiceConfigView service = mock(ServicePropertiesProvider.ServiceConfigView.class);
        when(service.getLabel()).thenReturn("Automation Service");
        when(service.getPath()).thenReturn("automationservice-sox");
        when(service.getGroup()).thenReturn(ServiceGroup.BACKEND);
        when(service.getTags()).thenReturn(List.of("api", "db"));
        when(this.servicePropertiesProvider.getServices()).thenReturn(Map.of("atmssox", service));

        final ManageOperatorUiTasks.OperatorUiServiceCatalogResponse actual = this.manageOperatorUiTasks.services();

        assertThat(actual.services()).singleElement().satisfies(option -> {
            assertThat(option.id()).isEqualTo("atmssox");
            assertThat(option.label()).isEqualTo("Automation Service");
            assertThat(option.path()).isEqualTo("automationservice-sox");
            assertThat(option.group()).isEqualTo("BACKEND");
            assertThat(option.tags()).containsExactly("api", "db");
        });
    }

    @Test
    void givenCreateCommand_whenCreate_thenCreateOpenTicketThroughStartUseCase() {
        final UUID ticketId = UUID.randomUUID();
        final Ticket ticket = this.ticket(ticketId, TicketStatus.OPEN);
        final OperatorUiCreateTaskCommand command = new OperatorUiCreateTaskCommand("SITIONIX-142", "task", List.of("atmssox"), null);
        when(this.startForgeAiTask.createOpen(any(ForgeAiStartCommand.class))).thenReturn(ticket);

        final ManageOperatorUiTasks.OperatorUiTaskMutationResponse actual = this.manageOperatorUiTasks.create(command);

        final ArgumentCaptor<ForgeAiStartCommand> captor = ArgumentCaptor.forClass(ForgeAiStartCommand.class);
        verify(this.startForgeAiTask).createOpen(captor.capture());
        assertThat(captor.getValue().getTicket()).isEqualTo("SITIONIX-142");
        assertThat(captor.getValue().getTask()).isEqualTo("task");
        assertThat(captor.getValue().getServiceIds()).containsExactly("atmssox");
        assertThat(actual.ticketId()).isEqualTo(ticketId);
        assertThat(actual.status()).isEqualTo("OPEN");
    }

    @Test
    void givenMissingCreateCommand_whenCreate_thenReject() {
        assertThatThrownBy(() -> this.manageOperatorUiTasks.create(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Create task command is required");
    }

    @Test
    void givenTicketId_whenExecute_thenExecuteOpenTicketThroughStartUseCase() {
        final UUID ticketId = UUID.randomUUID();
        when(this.startForgeAiTask.executeOpen(ticketId)).thenReturn(this.ticket(ticketId, TicketStatus.READY_TO_START));

        final ManageOperatorUiTasks.OperatorUiTaskMutationResponse actual = this.manageOperatorUiTasks.execute(ticketId);

        verify(this.startForgeAiTask).executeOpen(ticketId);
        assertThat(actual.ticketId()).isEqualTo(ticketId);
        assertThat(actual.status()).isEqualTo("READY_TO_START");
    }

    @Test
    void givenMissingTicketId_whenExecute_thenReject() {
        assertThatThrownBy(() -> this.manageOperatorUiTasks.execute(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ticket id is required");
    }

    private Ticket ticket(final UUID ticketId, final TicketStatus status) {
        return Ticket.builder()
                .id(ticketId)
                .ticketKey("SITIONIX-142")
                .status(status)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
