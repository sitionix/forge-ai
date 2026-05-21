package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementBeChangedFileDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementBeIntegrationFlowDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementBePersistenceChangeDTO;
import com.sitionix.forgeai.api.LaneScopeValidator;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class CompleteImplementBeLaneOrchestrationUseCaseTest {

    private CompleteImplementBeLaneOrchestrationUseCase completeImplementBeLaneOrchestrationUseCase;

    @Mock
    private LaneScopeValidator laneScopeValidator;

    @Mock
    private CreateAgentTask createAgentTask;

    @BeforeEach
    void setUp() {
        this.completeImplementBeLaneOrchestrationUseCase = new CompleteImplementBeLaneOrchestrationUseCase(
                this.laneScopeValidator,
                this.createAgentTask
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.laneScopeValidator, this.createAgentTask);
    }

    @Test
    void givenChangedFilesAndIntegrationData_whenComplete_thenCreateTestUnitAndTestItTasks() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteImplementBeLaneRequestDTO request = CompleteImplementBeLaneRequestDTO.builder()
                .scope("automationservice-sox")
                .summary("Implement create endpoint")
                .changedFiles(List.of(
                        ImplementBeChangedFileDTO.builder().path("application/src/main/java/CreateUseCase.java").reason("added create flow").build()
                ))
                .integrationFlows(List.of(
                        ImplementBeIntegrationFlowDTO.builder()
                                .name("Create flow")
                                .method(ImplementBeIntegrationFlowDTO.MethodEnum.POST)
                                .path("/api/v1/agent-actions")
                                .operationId("createAgentAction")
                                .summary("delegation")
                                .build()
                ))
                .persistenceChanges(List.of(
                        ImplementBePersistenceChangeDTO.builder()
                                .type(ImplementBePersistenceChangeDTO.TypeEnum.TABLE_CREATED)
                                .name("agent_action")
                                .summary("new document")
                                .build()
                ))
                .build();

        final ArgumentCaptor<AgentTicket> createdTicketCaptor = ArgumentCaptor.forClass(AgentTicket.class);

        //when
        this.completeImplementBeLaneOrchestrationUseCase.complete(ticketId, laneId, request);

        //then
        verify(this.laneScopeValidator).validateImplementBeCallbackScope(laneId, "automationservice-sox");
        verify(this.createAgentTask, times(2)).create(createdTicketCaptor.capture(), eq(laneId));

        final List<AgentTicket> createdTickets = createdTicketCaptor.getAllValues();
        assertThat(createdTickets).hasSize(2);
        assertThat(createdTickets.stream().map(AgentTicket::getAgent)).containsExactlyInAnyOrder(Agent.TEST_UNIT, Agent.TEST_IT);

        final AgentTicket<?> testUnitTicket = createdTickets.stream()
                .filter(value -> Objects.equals(value.getAgent(), Agent.TEST_UNIT))
                .findFirst()
                .orElseThrow();
        final AgentTicket<?> testItTicket = createdTickets.stream()
                .filter(value -> Objects.equals(value.getAgent(), Agent.TEST_IT))
                .findFirst()
                .orElseThrow();

        assertThat((TestUnitPayload) testUnitTicket.getPayload()).isEqualTo(TestUnitPayload.builder()
                .task("Write unit tests for backend changed files in automationservice-sox")
                .scope("automationservice-sox")
                .summary("Implement create endpoint")
                .changedFiles(Set.of("application/src/main/java/CreateUseCase.java :: added create flow"))
                .build());
        assertThat((TestItPayload) testItTicket.getPayload()).isEqualTo(TestItPayload.builder()
                .task("Write integration tests for backend integration and persistence changes in automationservice-sox")
                .scope("automationservice-sox")
                .summary("Implement create endpoint")
                .integrationFlows(Set.of("Create flow | POST /api/v1/agent-actions | createAgentAction | delegation"))
                .persistenceChanges(Set.of("TABLE_CREATED | agent_action | new document"))
                .build());
    }

    @Test
    void givenNoChangedFilesAndNoIntegrationData_whenComplete_thenMarkTestUnitAndTestItAsNotNeeded() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteImplementBeLaneRequestDTO request = CompleteImplementBeLaneRequestDTO.builder()
                .scope("automationservice-sox")
                .summary("Implement create endpoint")
                .build();

        //when
        this.completeImplementBeLaneOrchestrationUseCase.complete(ticketId, laneId, request);

        //then
        verify(this.laneScopeValidator).validateImplementBeCallbackScope(laneId, "automationservice-sox");
        verify(this.createAgentTask).markAsNotNeeded(laneId, "automationservice-sox", Agent.TEST_UNIT);
        verify(this.createAgentTask).markAsNotNeeded(laneId, "automationservice-sox", Agent.TEST_IT);
    }
}
