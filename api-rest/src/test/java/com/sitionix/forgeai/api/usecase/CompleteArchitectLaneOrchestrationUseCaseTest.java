package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.ArchitectApiRequest;
import com.app_afesox.fgaisox.api_first.dto.ArchitectEventRequest;
import com.app_afesox.fgaisox.api_first.dto.ArchitectImplementationHandoff;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.sitionix.forgeai.api.ScopeMismatchException;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class CompleteArchitectLaneOrchestrationUseCaseTest {

    private CompleteArchitectLaneOrchestrationUseCase completeArchitectLaneOrchestrationUseCase;

    @Mock
    private AgentTicketApiMapper agentTicketApiMapper;

    @Mock
    private CreateAgentTask createAgentTask;

    @Mock
    private ServicePropertiesProvider servicePropertiesProvider;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private ServicePropertiesProvider.ServiceConfigView serviceConfigView;

    @BeforeEach
    void setUp() {
        this.completeArchitectLaneOrchestrationUseCase = new CompleteArchitectLaneOrchestrationUseCase(
                this.agentTicketApiMapper,
                this.createAgentTask,
                this.servicePropertiesProvider,
                this.ticketRepository
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.agentTicketApiMapper, this.createAgentTask, this.servicePropertiesProvider, this.ticketRepository, this.serviceConfigView);
    }

    @Test
    void givenBackendScopeAndApiRequiredAndEventNotRequired_whenComplete_thenCreateBeApiAndEventTickets() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteArchitectLaneRequest request = CompleteArchitectLaneRequest.builder()
                .implementationHandoff(ArchitectImplementationHandoff.builder().scope("automationservice-sox").build())
                .apiRequest(ArchitectApiRequest.builder().required(Boolean.TRUE).build())
                .eventRequest(ArchitectEventRequest.builder().required(Boolean.FALSE).build())
                .build();
        final AgentTicket<ImplementBePayload> implementBeTicket = AgentTicket.<ImplementBePayload>builder().id(UUID.randomUUID()).build();
        final AgentTicket<ApiPayload> apiTicket = AgentTicket.<ApiPayload>builder().id(UUID.randomUUID()).build();
        final AgentTicket<EventPayload> eventTicket = AgentTicket.<EventPayload>builder().id(UUID.randomUUID()).build();
        final Lane lane = Lane.builder().id(laneId).scope("automationservice-sox").build();

        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));
        when(this.servicePropertiesProvider.getServices()).thenReturn(Map.of("atmssox", this.serviceConfigView));
        when(this.serviceConfigView.getPath()).thenReturn("automationservice-sox");
        when(this.serviceConfigView.getGroup()).thenReturn(ServiceGroup.BACKEND);
        when(this.agentTicketApiMapper.asImplementBeTicket(request, ticketId)).thenReturn(implementBeTicket);
        when(this.agentTicketApiMapper.asApiTicket(request, ticketId)).thenReturn(apiTicket);
        when(this.agentTicketApiMapper.asEventTicket(request, ticketId)).thenReturn(eventTicket);

        //when
        this.completeArchitectLaneOrchestrationUseCase.complete(ticketId, laneId, request);

        //then
        verify(this.servicePropertiesProvider).getServices();
        verify(this.ticketRepository).findByLaneId(laneId);
        verify(this.serviceConfigView).getPath();
        verify(this.serviceConfigView).getGroup();
        verify(this.agentTicketApiMapper).asImplementBeTicket(request, ticketId);
        verify(this.agentTicketApiMapper).asApiTicket(request, ticketId);
        verify(this.agentTicketApiMapper).asEventTicket(request, ticketId);
        verify(this.createAgentTask).create(implementBeTicket, laneId);
        verify(this.createAgentTask).create(apiTicket, laneId);
        verify(this.createAgentTask).create(eventTicket, laneId);
    }

    @Test
    void givenFrontendScopeAndApiNotRequiredAndEventRequired_whenComplete_thenCreateFeApiAndEventTickets() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteArchitectLaneRequest request = CompleteArchitectLaneRequest.builder()
                .implementationHandoff(ArchitectImplementationHandoff.builder().scope("frontendservice-sox").build())
                .apiRequest(ArchitectApiRequest.builder().required(Boolean.FALSE).build())
                .eventRequest(ArchitectEventRequest.builder().required(Boolean.TRUE).build())
                .build();
        final AgentTicket<ImplementFePayload> implementFeTicket = AgentTicket.<ImplementFePayload>builder().id(UUID.randomUUID()).build();
        final AgentTicket<ApiPayload> apiTicket = AgentTicket.<ApiPayload>builder().id(UUID.randomUUID()).build();
        final AgentTicket<EventPayload> eventTicket = AgentTicket.<EventPayload>builder().id(UUID.randomUUID()).build();
        final Lane lane = Lane.builder().id(laneId).scope("frontendservice-sox").build();

        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));
        when(this.servicePropertiesProvider.getServices()).thenReturn(Map.of("fessox", this.serviceConfigView));
        when(this.serviceConfigView.getPath()).thenReturn("frontendservice-sox");
        when(this.serviceConfigView.getGroup()).thenReturn(ServiceGroup.FRONTEND);
        when(this.agentTicketApiMapper.asImplementFeTicket(request, ticketId)).thenReturn(implementFeTicket);
        when(this.agentTicketApiMapper.asApiTicket(request, ticketId)).thenReturn(apiTicket);
        when(this.agentTicketApiMapper.asEventTicket(request, ticketId)).thenReturn(eventTicket);

        //when
        this.completeArchitectLaneOrchestrationUseCase.complete(ticketId, laneId, request);

        //then
        verify(this.servicePropertiesProvider).getServices();
        verify(this.ticketRepository).findByLaneId(laneId);
        verify(this.serviceConfigView).getPath();
        verify(this.serviceConfigView).getGroup();
        verify(this.agentTicketApiMapper).asImplementFeTicket(request, ticketId);
        verify(this.agentTicketApiMapper).asApiTicket(request, ticketId);
        verify(this.agentTicketApiMapper).asEventTicket(request, ticketId);
        verify(this.createAgentTask).create(implementFeTicket, laneId);
        verify(this.createAgentTask).create(apiTicket, laneId);
        verify(this.createAgentTask).create(eventTicket, laneId);
    }

    @Test
    void givenScopeMismatch_whenComplete_thenThrowScopeMismatchException() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteArchitectLaneRequest request = CompleteArchitectLaneRequest.builder()
                .implementationHandoff(ArchitectImplementationHandoff.builder().scope("automationservice-sox").build())
                .apiRequest(ArchitectApiRequest.builder().required(Boolean.TRUE).build())
                .eventRequest(ArchitectEventRequest.builder().required(Boolean.TRUE).build())
                .build();
        final Lane lane = Lane.builder().id(laneId).scope("backendforfrontendservice-sox").build();
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        //when //then
        assertThatThrownBy(() -> this.completeArchitectLaneOrchestrationUseCase.complete(ticketId, laneId, request))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessageContaining("Implementation scope mismatch");

        verify(this.ticketRepository).findByLaneId(laneId);
    }
}
