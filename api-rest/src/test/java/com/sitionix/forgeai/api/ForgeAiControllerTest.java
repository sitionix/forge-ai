package com.sitionix.forgeai.api;

import com.app_afesox.fgaisox.api_first.dto.CompleteApiLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteApiLaneResponse;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneResponse;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteItTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteItTestLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteReviewerLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUiTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUiTestLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadDataCheckDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadIntegrationFlowDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadIntegrationTestCaseDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadTestLaneRequirementsDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadUnitTestNoteDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeResponseDTO;
import com.sitionix.forgeai.api.usecase.CompleteApiLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteArchitectLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteItTestLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteQaLeadLaneOrchestrationUseCase;
import com.sitionix.forgeai.api.usecase.CompleteUnitTestLaneOrchestrationUseCase;
import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import com.sitionix.forgeai.domain.usecase.CompleteReviewerTask;
import com.sitionix.forgeai.domain.usecase.StartForgeAiTask;
import com.sitionix.forgeai.mapper.AgentTicketApiMapper;
import com.sitionix.forgeai.mapper.ForgeAiApiMapper;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import java.util.List;
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
    private CompleteQaLeadLaneOrchestrationUseCase completeQaLeadLaneOrchestrationUseCase;

    @Mock
    private CompleteItTestLaneOrchestrationUseCase completeItTestLaneOrchestrationUseCase;

    @Mock
    private CompleteUnitTestLaneOrchestrationUseCase completeUnitTestLaneOrchestrationUseCase;

    @Mock
    private CompleteReviewerTask completeReviewerTaskUseCase;

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
                this.completeQaLeadLaneOrchestrationUseCase,
                this.completeItTestLaneOrchestrationUseCase,
                this.completeUnitTestLaneOrchestrationUseCase,
                this.completeReviewerTaskUseCase
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
                this.completeQaLeadLaneOrchestrationUseCase,
                this.completeItTestLaneOrchestrationUseCase,
                this.completeUnitTestLaneOrchestrationUseCase,
                this.completeReviewerTaskUseCase
        );
    }

    @Test
    void givenTicketId_whenCompleteReviewerLane_thenReturnOkResponse() {
        //given
        final UUID ticketId = UUID.randomUUID();
        when(this.completeReviewerTaskUseCase.complete(ticketId)).thenReturn(UUID.randomUUID());

        //when
        final ResponseEntity<CompleteReviewerLaneResponseDTO> actual = this.forgeAiController.completeReviewerLane(ticketId);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isEqualTo(CompleteReviewerLaneResponseDTO.builder()
                .ticketId(ticketId)
                .status(HttpStatus.OK.name())
                .build());
        verify(this.completeReviewerTaskUseCase).complete(ticketId);
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
        final CompleteQaLeadLaneRequestDTO request = this.getBackendQaLeadRequest();

        //when
        final ResponseEntity<CompleteQaLeadLaneResponseDTO> actual = this.forgeAiController.completeQaLeadLane(ticketId, laneId, request);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isEqualTo(CompleteQaLeadLaneResponseDTO.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .status(HttpStatus.OK.name())
                .build());
        verify(this.completeQaLeadLaneOrchestrationUseCase).complete(ticketId, laneId, request);
    }

    @Test
    void givenImplementFeLaneRequest_whenCompleteImplementFeLane_thenReturnOkResponse() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteImplementFeLaneRequestDTO request = CompleteImplementFeLaneRequestDTO.builder()
                .scope("sitionix-spa")
                .summary("Implemented frontend changes for assigned flow.")
                .build();
        final AgentTicket<TestUiPayload> testUiTicket = AgentTicket.<TestUiPayload>builder().build();
        when(this.agentTicketApiMapper.asTestUiTicket(request, ticketId)).thenReturn(testUiTicket);

        //when
        final ResponseEntity<CompleteImplementFeLaneResponseDTO> actual = this.forgeAiController.completeImplementFeLane(ticketId, laneId, request);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isEqualTo(CompleteImplementFeLaneResponseDTO.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .status(HttpStatus.OK.name())
                .build());
        verify(this.laneScopeValidator).validateImplementFeCallbackScope(laneId, request.getScope());
        verify(this.agentTicketApiMapper).asTestUiTicket(request, ticketId);
        verify(this.completeAgentTasks).complete(laneId, List.<AgentTicket<? extends AgentTicketPayload>>of(testUiTicket));
    }

    @Test
    void givenUiTestLaneRequest_whenCompleteUiTestLane_thenReturnOkResponse() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteUiTestLaneRequestDTO request = CompleteUiTestLaneRequestDTO.builder()
                .scope("sitionix-spa")
                .summary("UI tests completed")
                .build();

        //when
        final ResponseEntity<CompleteUiTestLaneResponseDTO> actual = this.forgeAiController.completeUiTestLane(ticketId, laneId, request);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isEqualTo(CompleteUiTestLaneResponseDTO.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .status(HttpStatus.OK.name())
                .build());
        verify(this.laneScopeValidator).validateTestUiCallbackScope(laneId, request.getScope());
        verify(this.completeAgentTasks).complete(laneId, List.of());
    }

    @Test
    void givenItTestLaneRequest_whenCompleteItTestLane_thenReturnOkResponse() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteItTestLaneRequestDTO request = CompleteItTestLaneRequestDTO.builder()
                .scope("automationservice-sox")
                .summary("Completed integration tests for backend flow.")
                .coveredCases(List.of("Create agent action successfully"))
                .build();

        //when
        final ResponseEntity<CompleteItTestLaneResponseDTO> actual = this.forgeAiController.completeItTestLane(ticketId, laneId, request);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isEqualTo(CompleteItTestLaneResponseDTO.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .status(HttpStatus.OK.name())
                .build());
        verify(this.completeItTestLaneOrchestrationUseCase).complete(ticketId, laneId, request);
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

    private CompleteQaLeadLaneRequestDTO getBackendQaLeadRequest() {
        return CompleteQaLeadLaneRequestDTO.builder()
                .scope("automationservice-sox")
                .summary("Prepared QA context for backend testing.")
                .testLaneRequirements(QaLeadTestLaneRequirementsDTO.builder()
                        .unitTestRequired(true)
                        .integrationTestRequired(true)
                        .uiTestRequired(false)
                        .build())
                .integrationTestCases(List.of(this.getIntegrationTestCase()))
                .unitTestNotes(List.of(this.getUnitTestNote()))
                .build();
    }

    private QaLeadIntegrationTestCaseDTO getIntegrationTestCase() {
        return QaLeadIntegrationTestCaseDTO.builder()
                .title("Create agent action successfully")
                .flow(QaLeadIntegrationFlowDTO.builder()
                        .name("Create agent action")
                        .method(QaLeadIntegrationFlowDTO.MethodEnum.POST)
                        .path("/api/v1/agent-actions")
                        .build())
                .given(List.of("ticket exists"))
                .when(List.of("POST request submitted"))
                .then(List.of("response 200"))
                .dataChecks(List.of(QaLeadDataCheckDTO.builder()
                        .target("agent ticket persisted")
                        .expectation("created record")
                        .build()))
                .priority(QaLeadIntegrationTestCaseDTO.PriorityEnum.HIGH)
                .build();
    }

    private QaLeadUnitTestNoteDTO getUnitTestNote() {
        return QaLeadUnitTestNoteDTO.builder()
                .target("CreateAgentActionUseCase")
                .note("Validate missing title handling")
                .build();
    }
}
