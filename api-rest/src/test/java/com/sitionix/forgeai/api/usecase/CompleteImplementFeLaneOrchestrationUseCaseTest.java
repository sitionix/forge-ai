package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.sitionix.forgeai.api.LaneScopeValidator;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeCompletionPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class CompleteImplementFeLaneOrchestrationUseCaseTest {

    private CompleteImplementFeLaneOrchestrationUseCase completeImplementFeLaneOrchestrationUseCase;

    @Mock
    private LaneScopeValidator laneScopeValidator;

    @Mock
    private ServicePropertiesProvider servicePropertiesProvider;

    @Mock
    private AgentTicketApiMapper agentTicketApiMapper;

    @Mock
    private AgentTicketRepository agentTicketRepository;

    @Mock
    private CreateAgentTask createAgentTask;

    @BeforeEach
    void setUp() {
        this.completeImplementFeLaneOrchestrationUseCase = new CompleteImplementFeLaneOrchestrationUseCase(
                this.laneScopeValidator,
                this.servicePropertiesProvider,
                this.agentTicketApiMapper,
                this.agentTicketRepository,
                this.createAgentTask
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(
                this.laneScopeValidator,
                this.servicePropertiesProvider,
                this.agentTicketApiMapper,
                this.agentTicketRepository,
                this.createAgentTask
        );
    }

    @Test
    void givenFrontendScope_whenComplete_thenStoreReportAndMarkTestUiNotNeeded() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteImplementFeLaneRequestDTO request = CompleteImplementFeLaneRequestDTO.builder()
                .scope("sitionix-spa")
                .summary("Implemented frontend changes for assigned flow.")
                .build();
        final ServicePropertiesProvider.ServiceConfigView service = mock(ServicePropertiesProvider.ServiceConfigView.class);
        final AgentTicket<ImplementFeCompletionPayload> completionReport = mock(AgentTicket.class);
        when(this.servicePropertiesProvider.getServices()).thenReturn(Map.of("sitionix-spa", service));
        when(service.getPath()).thenReturn("sitionix-spa");
        when(service.getGroup()).thenReturn(ServiceGroup.FRONTEND);
        when(this.agentTicketApiMapper.asImplementFeCompletionTicket(request, ticketId, laneId)).thenReturn(completionReport);

        //when
        this.completeImplementFeLaneOrchestrationUseCase.complete(ticketId, laneId, request);

        //then
        verify(this.laneScopeValidator).validateImplementFeCompletion(ticketId, laneId, "sitionix-spa");
        verify(this.servicePropertiesProvider).getServices();
        verify(service).getPath();
        verify(service).getGroup();
        verify(this.agentTicketApiMapper).asImplementFeCompletionTicket(request, ticketId, laneId);
        final ArgumentCaptor<AgentTicket<ImplementFeCompletionPayload>> reportCaptor = ArgumentCaptor.forClass(AgentTicket.class);
        verify(this.agentTicketRepository).save(reportCaptor.capture());
        verify(this.createAgentTask).markAsNotNeeded(laneId, "sitionix-spa", Agent.TEST_UI);
        assertThat(reportCaptor.getValue()).isEqualTo(completionReport);
    }

    @Test
    void givenBackendScope_whenComplete_thenThrowConflict() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteImplementFeLaneRequestDTO request = CompleteImplementFeLaneRequestDTO.builder()
                .scope("automationservice-sox")
                .summary("Implemented frontend changes for assigned flow.")
                .build();
        final ServicePropertiesProvider.ServiceConfigView service = mock(ServicePropertiesProvider.ServiceConfigView.class);
        when(this.servicePropertiesProvider.getServices()).thenReturn(Map.of("automationservice-sox", service));
        when(service.getPath()).thenReturn("automationservice-sox");
        when(service.getGroup()).thenReturn(ServiceGroup.BACKEND);

        //when then
        assertThatThrownBy(() -> this.completeImplementFeLaneOrchestrationUseCase.complete(ticketId, laneId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("Implement-fe scope must be frontend");

        verify(this.laneScopeValidator).validateImplementFeCompletion(ticketId, laneId, "automationservice-sox");
        verify(this.servicePropertiesProvider).getServices();
        verify(service).getPath();
        verify(service, times(2)).getGroup();
    }
}
