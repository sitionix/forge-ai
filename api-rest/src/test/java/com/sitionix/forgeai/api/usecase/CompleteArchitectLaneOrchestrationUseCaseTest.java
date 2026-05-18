package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.ArchitectApiRequest;
import com.app_afesox.fgaisox.api_first.dto.ArchitectEventRequest;
import com.app_afesox.fgaisox.api_first.dto.ArchitectImplementationHandoff;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.sitionix.forgeai.domain.model.CompleteArchitectLaneCommand;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.usecase.CompleteArchitectLane;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompleteArchitectLaneOrchestrationUseCaseTest {

    @Mock
    private AgentTicketApiMapper agentTicketApiMapper;

    @Mock
    private ServicePropertiesProvider servicePropertiesProvider;

    @Mock
    private CompleteArchitectLane completeArchitectLane;

    @Mock
    private ServicePropertiesProvider.ServiceConfigView serviceConfigView;

    private CompleteArchitectLaneOrchestrationUseCase completeArchitectLaneOrchestrationUseCase;

    @BeforeEach
    void setUp() {
        this.completeArchitectLaneOrchestrationUseCase = new CompleteArchitectLaneOrchestrationUseCase(
                this.agentTicketApiMapper,
                this.servicePropertiesProvider,
                this.completeArchitectLane
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.agentTicketApiMapper, this.servicePropertiesProvider, this.completeArchitectLane, this.serviceConfigView);
    }

    @Test
    void givenBackendScope_whenComplete_thenBuildCommandWithImplementBeTicket() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteArchitectLaneRequest request = this.getCompleteArchitectLaneRequest("automationservice-sox");
        final AgentTicket<ImplementBePayload> implementBeTicket = AgentTicket.<ImplementBePayload>builder().build();
        final AgentTicket<ApiPayload> apiTicket = AgentTicket.<ApiPayload>builder().build();
        final AgentTicket<EventPayload> eventTicket = AgentTicket.<EventPayload>builder().build();

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
        verify(this.serviceConfigView).getPath();
        verify(this.serviceConfigView).getGroup();
        verify(this.agentTicketApiMapper).asImplementBeTicket(request, ticketId);
        verify(this.agentTicketApiMapper).asApiTicket(request, ticketId);
        verify(this.agentTicketApiMapper).asEventTicket(request, ticketId);

        final ArgumentCaptor<CompleteArchitectLaneCommand> captor = ArgumentCaptor.forClass(CompleteArchitectLaneCommand.class);
        verify(this.completeArchitectLane).complete(captor.capture());
        final CompleteArchitectLaneCommand actual = captor.getValue();
        assertThat(actual.getSourceLaneId()).isEqualTo(laneId);
        assertThat(actual.getImplementBeTicket()).isEqualTo(implementBeTicket);
        assertThat(actual.getImplementFeTicket()).isNull();
        assertThat(actual.getApiTicket()).isEqualTo(apiTicket);
        assertThat(actual.getEventTicket()).isEqualTo(eventTicket);
        assertThat(actual.getApiRequired()).isTrue();
        assertThat(actual.getApiScope()).isEqualTo("GLOBAL");
        assertThat(actual.getEventRequired()).isFalse();
        assertThat(actual.getEventScope()).isEqualTo("GLOBAL");
    }

    @Test
    void givenFrontendScope_whenComplete_thenBuildCommandWithImplementFeTicket() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteArchitectLaneRequest request = this.getCompleteArchitectLaneRequest("frontendservice-sox");
        final AgentTicket<ImplementFePayload> implementFeTicket = AgentTicket.<ImplementFePayload>builder().build();
        final AgentTicket<ApiPayload> apiTicket = AgentTicket.<ApiPayload>builder().build();
        final AgentTicket<EventPayload> eventTicket = AgentTicket.<EventPayload>builder().build();

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
        verify(this.serviceConfigView).getPath();
        verify(this.serviceConfigView).getGroup();
        verify(this.agentTicketApiMapper).asImplementFeTicket(request, ticketId);
        verify(this.agentTicketApiMapper).asApiTicket(request, ticketId);
        verify(this.agentTicketApiMapper).asEventTicket(request, ticketId);

        final ArgumentCaptor<CompleteArchitectLaneCommand> captor = ArgumentCaptor.forClass(CompleteArchitectLaneCommand.class);
        verify(this.completeArchitectLane).complete(captor.capture());
        final CompleteArchitectLaneCommand actual = captor.getValue();
        assertThat(actual.getSourceLaneId()).isEqualTo(laneId);
        assertThat(actual.getImplementBeTicket()).isNull();
        assertThat(actual.getImplementFeTicket()).isEqualTo(implementFeTicket);
    }

    private CompleteArchitectLaneRequest getCompleteArchitectLaneRequest(final String scope) {
        return CompleteArchitectLaneRequest.builder()
                .implementationHandoff(ArchitectImplementationHandoff.builder().scope(scope).build())
                .apiRequest(ArchitectApiRequest.builder().required(Boolean.TRUE).scope("GLOBAL").build())
                .eventRequest(ArchitectEventRequest.builder().required(Boolean.FALSE).scope("GLOBAL").build())
                .build();
    }
}
