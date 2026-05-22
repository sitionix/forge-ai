package com.sitionix.forgeai.api;

import com.app_afesox.fgaisox.api_first.dto.CompleteApiLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteApiLaneResponse;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneResponse;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeResponseDTO;
import com.sitionix.forgeai.api.usecase.CompleteApiLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteArchitectLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteUnitTestLaneOrchestrationUseCase;
import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import com.sitionix.forgeai.domain.usecase.StartForgeAiTask;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import com.sitionix.forgeai.mapper.ForgeAiApiMapper;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private CompleteAgentTasks completeAgentTasks;

    @Mock
    private LaneScopeValidator laneScopeValidator;

    @Mock
    private CompleteArchitectLaneOrchestrationUseCase completeArchitectLaneOrchestrationUseCase;

    @Mock
    private CompleteApiLaneOrchestrationUseCase completeApiLaneOrchestrationUseCase;

    @Mock
    private CompleteUnitTestLaneOrchestrationUseCase completeUnitTestLaneOrchestrationUseCase;

    @BeforeEach
    void setUp() {
        this.forgeAiController = new ForgeAiController(
                this.startForgeAiTask,
                this.forgeAiApiMapper,
                this.terminalTtyResolver,
                this.agentTicketApiMapper,
                this.completeAgentTasks,
                this.laneScopeValidator,
                this.completeArchitectLaneOrchestrationUseCase,
                this.completeApiLaneOrchestrationUseCase,
                this.completeUnitTestLaneOrchestrationUseCase
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(
                this.startForgeAiTask,
                this.forgeAiApiMapper,
                this.terminalTtyResolver,
                this.agentTicketApiMapper,
                this.completeAgentTasks,
                this.laneScopeValidator,
                this.completeArchitectLaneOrchestrationUseCase,
                this.completeApiLaneOrchestrationUseCase,
                this.completeUnitTestLaneOrchestrationUseCase
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
    void givenArchitectLaneRequest_whenCompleteArchitectLane_thenReturnOkResponse() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteArchitectLaneRequest request = CompleteArchitectLaneRequest.builder().build();

        //when
        final ResponseEntity<CompleteArchitectLaneResponse> actual = this.forgeAiController.completeArchitectLane(ticketId, laneId, request);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isEqualTo(CompleteArchitectLaneResponse.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .laneStatus(HttpStatus.OK.name())
                .build());
        verify(this.completeArchitectLaneOrchestrationUseCase).complete(ticketId, laneId, request);
    }

    @Test
    void givenApiLaneRequest_whenCompleteApiLane_thenReturnOkResponse() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteApiLaneRequest request = CompleteApiLaneRequest.builder().build();

        //when
        final ResponseEntity<CompleteApiLaneResponse> actual = this.forgeAiController.completeApiLane(ticketId, laneId, request);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isEqualTo(CompleteApiLaneResponse.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .laneStatus(HttpStatus.OK.name())
                .build());
        verify(this.completeApiLaneOrchestrationUseCase).complete(ticketId, laneId, request);
    }

    @Test
    void givenImplementBeLaneRequest_whenCompleteImplementBeLane_thenReturnOkResponse() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteImplementBeLaneRequestDTO request = CompleteImplementBeLaneRequestDTO.builder().scope("automationservice-sox").build();
        final AgentTicket<TestUnitPayload> testUnitTicket = AgentTicket.<TestUnitPayload>builder().build();
        final AgentTicket<TestItPayload> testItTicket = AgentTicket.<TestItPayload>builder().build();
        when(this.agentTicketApiMapper.asTestUnitTicket(request, ticketId)).thenReturn(testUnitTicket);
        when(this.agentTicketApiMapper.asTestItTicket(request, ticketId)).thenReturn(testItTicket);

        //when
        final ResponseEntity<CompleteImplementBeLaneResponseDTO> actual = this.forgeAiController.completeImplementBeLane(ticketId, laneId, request);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isEqualTo(CompleteImplementBeLaneResponseDTO.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .status(HttpStatus.OK.name())
                .build());
        verify(this.laneScopeValidator).validateImplementBeCallbackScope(laneId, request.getScope());
        verify(this.agentTicketApiMapper).asTestUnitTicket(request, ticketId);
        verify(this.agentTicketApiMapper).asTestItTicket(request, ticketId);
        verify(this.completeAgentTasks).complete(laneId, List.<AgentTicket<? extends AgentTicketPayload>>of(testUnitTicket, testItTicket));
    }

    @Test
    void givenBackendQaLeadLaneRequest_whenCompleteQaLeadLane_thenReturnOkResponse() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteQaLeadLaneRequestDTO request = CompleteQaLeadLaneRequestDTO.builder().scope("automationservice-sox").build();
        final AgentTicket<TestUnitPayload> testUnitTicket = AgentTicket.<TestUnitPayload>builder().build();
        final AgentTicket<TestItPayload> testItTicket = AgentTicket.<TestItPayload>builder().build();
        when(this.agentTicketApiMapper.asTestUnitTicket(request, ticketId)).thenReturn(testUnitTicket);
        when(this.agentTicketApiMapper.asTestItTicket(request, ticketId)).thenReturn(testItTicket);

        //when
        final ResponseEntity<CompleteQaLeadLaneResponseDTO> actual = this.forgeAiController.completeQaLeadLane(ticketId, laneId, request);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isEqualTo(CompleteQaLeadLaneResponseDTO.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .status(HttpStatus.OK.name())
                .build());
        verify(this.laneScopeValidator).validateQaLeadCallbackScope(laneId, request.getScope());
        verify(this.agentTicketApiMapper).asTestUnitTicket(request, ticketId);
        verify(this.agentTicketApiMapper).asTestItTicket(request, ticketId);
        verify(this.completeAgentTasks).complete(laneId, List.<AgentTicket<? extends AgentTicketPayload>>of(testUnitTicket, testItTicket));
    }

    @Test
    void givenFrontendQaLeadLaneRequest_whenCompleteQaLeadLane_thenReturnOkResponse() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteQaLeadLaneRequestDTO request = CompleteQaLeadLaneRequestDTO.builder().scope("backendforfrontendservice-sox").build();
        final AgentTicket<TestUnitPayload> testUnitTicket = AgentTicket.<TestUnitPayload>builder().build();
        final AgentTicket<TestItPayload> testItTicket = AgentTicket.<TestItPayload>builder().build();
        when(this.agentTicketApiMapper.asTestUnitTicket(request, ticketId)).thenReturn(testUnitTicket);
        when(this.agentTicketApiMapper.asTestItTicket(request, ticketId)).thenReturn(testItTicket);

        //when
        final ResponseEntity<CompleteQaLeadLaneResponseDTO> actual = this.forgeAiController.completeQaLeadLane(ticketId, laneId, request);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isEqualTo(CompleteQaLeadLaneResponseDTO.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .status(HttpStatus.OK.name())
                .build());
        verify(this.laneScopeValidator).validateQaLeadCallbackScope(laneId, request.getScope());
        verify(this.agentTicketApiMapper).asTestUnitTicket(request, ticketId);
        verify(this.agentTicketApiMapper).asTestItTicket(request, ticketId);
        verify(this.completeAgentTasks).complete(laneId, List.<AgentTicket<? extends AgentTicketPayload>>of(testUnitTicket, testItTicket));
    }

    @Test
    void givenUnitTestLaneRequest_whenCompleteUnitTestLane_thenReturnOkResponse() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteUnitTestLaneRequestDTO request = CompleteUnitTestLaneRequestDTO.builder().scope("automationservice-sox").build();

        //when
        final ResponseEntity<CompleteUnitTestLaneResponseDTO> actual = this.forgeAiController.completeUnitTestLane(ticketId, laneId, request);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isEqualTo(CompleteUnitTestLaneResponseDTO.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .status(HttpStatus.OK.name())
                .build());
        verify(this.completeUnitTestLaneOrchestrationUseCase).complete(ticketId, laneId, request);
    }
}
