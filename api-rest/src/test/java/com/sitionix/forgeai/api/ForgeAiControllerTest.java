package com.sitionix.forgeai.api;

import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneResponse;
import com.app_afesox.fgaisox.api_first.dto.ArchitectApiRequest;
import com.app_afesox.fgaisox.api_first.dto.ArchitectEventRequest;
import com.app_afesox.fgaisox.api_first.dto.ArchitectImplementationHandoff;
import com.app_afesox.fgaisox.api_first.dto.StartForgeRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeResponseDTO;
import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import com.sitionix.forgeai.domain.usecase.StartForgeAiTask;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import com.sitionix.forgeai.mapper.ForgeAiApiMapper;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForgeAiControllerTest {

    private ForgeAiController forgeAiController;

    @Mock
    private StartForgeAiTask startForgeAiTask;

    @Mock
    private ForgeAiApiMapper forgeAiApiMapper;

    @Mock
    private TerminalTtyResolver terminalTtyResolver;

    @Mock
    private AgentTicketApiMapper agentTicketApiMapper;

    @Mock
    private CreateAgentTask createAgentTask;

    @Mock
    private ServicePropertiesProvider servicePropertiesProvider;

    @Mock
    private ServicePropertiesProvider.ServiceConfigView serviceConfigView;

    @BeforeEach
    void setUp() {
        this.forgeAiController = new ForgeAiController(
                this.startForgeAiTask,
                this.forgeAiApiMapper,
                this.terminalTtyResolver,
                this.agentTicketApiMapper,
                this.createAgentTask,
                this.servicePropertiesProvider
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(
                this.startForgeAiTask,
                this.forgeAiApiMapper,
                this.terminalTtyResolver,
                this.agentTicketApiMapper,
                this.createAgentTask,
                this.servicePropertiesProvider,
                this.serviceConfigView
        );
    }

    @Test
    void givenValidStartForgeRequestDTO_whenStartForge_thenReturnCreatedResponseEntity() {
        //given
        final StartForgeRequestDTO requestDTO = mock(StartForgeRequestDTO.class);
        final ForgeAiStartCommand command = mock(ForgeAiStartCommand.class);
        final Ticket startedTask = mock(Ticket.class);
        final StartForgeResponseDTO responseDTO = mock(StartForgeResponseDTO.class);

        when(this.terminalTtyResolver.resolve()).thenReturn("/dev/ttys008");
        when(this.forgeAiApiMapper.asForgeAiStartCommand(requestDTO, "/dev/ttys008")).thenReturn(command);
        when(this.startForgeAiTask.execute(command)).thenReturn(startedTask);
        when(this.forgeAiApiMapper.asStartForgeResponseDto(startedTask)).thenReturn(responseDTO);

        //when
        final ResponseEntity<StartForgeResponseDTO> actual = this.forgeAiController.startForge(requestDTO);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(actual.getBody()).isEqualTo(responseDTO);
        verify(this.terminalTtyResolver).resolve();
        verify(this.forgeAiApiMapper).asForgeAiStartCommand(requestDTO, "/dev/ttys008");
        verify(this.startForgeAiTask).execute(command);
        verify(this.forgeAiApiMapper).asStartForgeResponseDto(startedTask);
    }

    @Test
    void givenBackendArchitectLaneRequest_whenCompleteArchitectLane_thenCreateImplementAndApiAndMarkEventNotNeeded() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteArchitectLaneRequest request = CompleteArchitectLaneRequest.builder()
                .implementationHandoff(ArchitectImplementationHandoff.builder().scope("automationservice-sox").build())
                .apiRequest(ArchitectApiRequest.builder().required(Boolean.TRUE).build())
                .eventRequest(ArchitectEventRequest.builder().required(Boolean.FALSE).build())
                .build();
        final AgentTicket<ImplementBePayload> implementBeTicket = AgentTicket.<ImplementBePayload>builder().scope("automationservice-sox").build();
        final AgentTicket<ApiPayload> apiTicket = AgentTicket.<ApiPayload>builder().scope("automationservice-sox").build();

        when(this.servicePropertiesProvider.getServices()).thenReturn(Map.of("atmssox", this.serviceConfigView));
        when(this.serviceConfigView.getPath()).thenReturn("automationservice-sox");
        when(this.serviceConfigView.getGroup()).thenReturn(ServiceGroup.BACKEND);
        when(this.agentTicketApiMapper.asImplementBeTicket(request, ticketId)).thenReturn(implementBeTicket);
        when(this.agentTicketApiMapper.asApiTicket(request, ticketId)).thenReturn(apiTicket);

        //when
        final ResponseEntity<CompleteArchitectLaneResponse> actual = this.forgeAiController.completeArchitectLane(ticketId, laneId, request);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isEqualTo(CompleteArchitectLaneResponse.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .laneStatus(HttpStatus.OK.name())
                .build());
        assertThat(apiTicket.getScope()).isEqualTo(ScopeMode.GLOBAL_SCOPE);
        verify(this.servicePropertiesProvider).getServices();
        verify(this.serviceConfigView).getPath();
        verify(this.serviceConfigView).getGroup();
        verify(this.agentTicketApiMapper).asImplementBeTicket(request, ticketId);
        verify(this.agentTicketApiMapper).asApiTicket(request, ticketId);
        verify(this.createAgentTask).create(implementBeTicket, laneId);
        verify(this.createAgentTask).create(apiTicket, laneId);
        verify(this.createAgentTask).markAsNotNeeded(laneId, ScopeMode.GLOBAL_SCOPE, Agent.EVENT);
        verify(this.agentTicketApiMapper, never()).asEventTicket(request, ticketId);
    }
}
