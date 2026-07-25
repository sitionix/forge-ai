package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.application.operator.TicketOperatorEventService;
import com.sitionix.forgeai.domain.exception.TicketNotFoundException;
import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecutionStatus;
import com.sitionix.forgeai.domain.model.operator.task.OperatorUiCreateTaskCommand;
import com.sitionix.forgeai.domain.model.operator.task.OperatorUiServiceCatalogResponse;
import com.sitionix.forgeai.domain.model.operator.task.OperatorUiTaskMutationResponse;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.TicketStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.LaneExecutionRepository;
import com.sitionix.forgeai.domain.repository.TicketOperatorRunRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.props.ServiceConfigView;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.usecase.ManageOperatorUiTasks;
import com.sitionix.forgeai.domain.usecase.ManageTicketOperatorRuns;
import com.sitionix.forgeai.domain.usecase.StartForgeAiTask;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import static org.mockito.Mockito.never;
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
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private AgentTicketRepository agentTicketRepository;
    @Mock
    private LaneExecutionRepository laneExecutionRepository;
    @Mock
    private TicketOperatorRunRepository ticketOperatorRunRepository;
    @Mock
    private ManageTicketOperatorRuns manageTicketOperatorRuns;
    @Mock
    private TicketOperatorEventService ticketOperatorEventService;

    @BeforeEach
    void setUp() {
        this.manageOperatorUiTasks = new ManageOperatorUiTasksUseCase(
                this.servicePropertiesProvider,
                this.startForgeAiTask,
                this.ticketRepository,
                this.agentTicketRepository,
                this.laneExecutionRepository,
                this.ticketOperatorRunRepository,
                this.manageTicketOperatorRuns,
                this.ticketOperatorEventService
        );
    }

    @Test
    void givenServicesConfigured_whenServices_thenReturnCatalogFromYamlProvider() {
        final ServiceConfigView service = mock(ServiceConfigView.class);
        when(service.getLabel()).thenReturn("Automation Service");
        when(service.getPath()).thenReturn("automationservice-sox");
        when(service.getGroup()).thenReturn(ServiceGroup.BACKEND);
        when(service.getTags()).thenReturn(List.of("api", "db"));
        when(this.servicePropertiesProvider.getServices()).thenReturn(Map.of("atmssox", service));

        final OperatorUiServiceCatalogResponse actual = this.manageOperatorUiTasks.services();

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

        final OperatorUiTaskMutationResponse actual = this.manageOperatorUiTasks.create(command);

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

        final OperatorUiTaskMutationResponse actual = this.manageOperatorUiTasks.execute(ticketId);

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

    @Test
    void givenFailedLaneExecution_whenRetryLane_thenMoveLaneBackToReadyToStart() {
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.of(this.ticketWithLane(ticketId, laneId, LaneStatus.COMPLETED)));
        when(this.laneExecutionRepository.findByTicketId(ticketId)).thenReturn(List.of(LaneExecution.builder()
                .id(UUID.randomUUID())
                .ticketId(ticketId)
                .laneId(laneId)
                .agentId("api")
                .scope("GLOBAL")
                .status(LaneExecutionStatus.FAILED)
                .threadId("thr_failed")
                .currentStepId("generation")
                .updatedAt(LocalDateTime.now())
                .build()));

        this.manageOperatorUiTasks.retryLane(ticketId, laneId);

        verify(this.ticketRepository).restartLane(ticketId, laneId);
    }

    @Test
    void givenActiveLane_whenRetryLane_thenReject() {
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.of(this.ticketWithLane(ticketId, laneId, LaneStatus.IN_PROGRESS)));

        assertThatThrownBy(() -> this.manageOperatorUiTasks.retryLane(ticketId, laneId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot retry active lane: laneId=" + laneId);
    }

    @Test
    void givenTicketWithoutActiveExecutions_whenDelete_thenRemoveTicketData() {
        final UUID ticketId = UUID.randomUUID();
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.of(this.ticket(ticketId, TicketStatus.OPEN)));
        when(this.laneExecutionRepository.findActiveExecutionsByTicketId(ticketId)).thenReturn(List.of());

        this.manageOperatorUiTasks.delete(ticketId);

        verify(this.manageTicketOperatorRuns, never()).interruptTicket(any(UUID.class), any(String.class));
        verify(this.agentTicketRepository).deleteByTicketId(ticketId);
        verify(this.laneExecutionRepository).deleteByTicketId(ticketId);
        verify(this.ticketOperatorRunRepository).deleteByTicketId(ticketId);
        verify(this.ticketRepository).deleteById(ticketId);
        verify(this.ticketOperatorEventService).clear(ticketId);
    }

    @Test
    void givenTicketWithActiveExecutions_whenDelete_thenInterruptBeforeRemovingTicketData() {
        final UUID ticketId = UUID.randomUUID();
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.of(this.ticket(ticketId, TicketStatus.READY_TO_START)));
        when(this.laneExecutionRepository.findActiveExecutionsByTicketId(ticketId)).thenReturn(List.of(LaneExecution.builder()
                .id(UUID.randomUUID())
                .ticketId(ticketId)
                .laneId(UUID.randomUUID())
                .agentId("api")
                .scope("GLOBAL")
                .status(LaneExecutionStatus.STEP_RUNNING)
                .updatedAt(LocalDateTime.now())
                .build()));

        this.manageOperatorUiTasks.delete(ticketId);

        verify(this.manageTicketOperatorRuns).interruptTicket(ticketId, "OPERATOR_UI_TICKET_DELETED");
        verify(this.ticketRepository).deleteById(ticketId);
    }

    @Test
    void givenUnknownTicket_whenDelete_thenRejectAsNotFound() {
        final UUID ticketId = UUID.randomUUID();
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.manageOperatorUiTasks.delete(ticketId))
                .isInstanceOf(TicketNotFoundException.class)
                .hasMessage("Ticket not found: " + ticketId);
    }

    @Test
    void givenMissingTicketId_whenDelete_thenReject() {
        assertThatThrownBy(() -> this.manageOperatorUiTasks.delete(null))
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

    private Ticket ticketWithLane(final UUID ticketId, final UUID laneId, final LaneStatus laneStatus) {
        return Ticket.builder()
                .id(ticketId)
                .ticketKey("SITIONIX-142")
                .status(TicketStatus.IN_PROGRESS)
                .lanes(List.of(Lane.builder()
                        .id(laneId)
                        .agent(Agent.API)
                        .scope("GLOBAL")
                        .status(laneStatus)
                        .build()))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
