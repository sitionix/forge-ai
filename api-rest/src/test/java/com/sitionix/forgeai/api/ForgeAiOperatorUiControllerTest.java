package com.sitionix.forgeai.api;

import com.app_afesox.fgaisox.api_first.dto.OperatorUiCreateTaskRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiLaneDetailResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiServiceCatalogResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiTaskMutationResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiTicketGraphResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiTicketListResponseDTO;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel.OperatorUiLaneDetailResponse;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel.OperatorUiTicketGraphResponse;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel.OperatorUiTicketListResponse;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorAgentConfigResponse;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorConfigResourceSaveRequest;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorConfigResourceView;
import com.sitionix.forgeai.domain.usecase.ManageOperatorUiTasks;
import com.sitionix.forgeai.domain.usecase.ManageOperatorUiTasks.OperatorUiCreateTaskCommand;
import com.sitionix.forgeai.domain.usecase.ManageOperatorUiTasks.OperatorUiServiceCatalogResponse;
import com.sitionix.forgeai.domain.usecase.ManageOperatorUiTasks.OperatorUiTaskMutationResponse;
import com.sitionix.forgeai.mapper.ForgeAiOperatorApiMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForgeAiOperatorUiControllerTest {

    private static final UUID TICKET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LANE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private ForgeAiOperatorUiController controller;

    @Mock
    private GetOperatorUiReadModel getOperatorUiReadModel;
    @Mock
    private ManageOperatorUiTasks manageOperatorUiTasks;
    @Mock
    private ManageOperatorAgentConfig manageOperatorAgentConfig;
    @Mock
    private ForgeAiOperatorApiMapper forgeAiOperatorApiMapper;

    @BeforeEach
    void setUp() {
        this.controller = new ForgeAiOperatorUiController(
                this.getOperatorUiReadModel,
                this.manageOperatorUiTasks,
                this.manageOperatorAgentConfig,
                this.forgeAiOperatorApiMapper
        );
    }

    @Test
    void givenLimit_whenGetOperatorUiTickets_thenDelegateToUseCase() {
        final OperatorUiTicketListResponse response = new OperatorUiTicketListResponse(List.of());
        final OperatorUiTicketListResponseDTO responseDto = OperatorUiTicketListResponseDTO.builder()
                .tickets(List.of())
                .build();
        when(this.getOperatorUiReadModel.tickets(25)).thenReturn(response);
        when(this.forgeAiOperatorApiMapper.asOperatorUiTicketListResponse(response)).thenReturn(responseDto);

        final ResponseEntity<OperatorUiTicketListResponseDTO> result = this.controller.getOperatorUiTickets(25);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(responseDto);
    }

    @Test
    void givenServicesRequest_whenGetOperatorUiServices_thenDelegateToUseCase() {
        final OperatorUiServiceCatalogResponse response = new OperatorUiServiceCatalogResponse(List.of());
        final OperatorUiServiceCatalogResponseDTO responseDto = OperatorUiServiceCatalogResponseDTO.builder()
                .services(List.of())
                .build();
        when(this.manageOperatorUiTasks.services()).thenReturn(response);
        when(this.forgeAiOperatorApiMapper.asOperatorUiServiceCatalogResponse(response)).thenReturn(responseDto);

        final ResponseEntity<OperatorUiServiceCatalogResponseDTO> result = this.controller.getOperatorUiServices();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(responseDto);
    }

    @Test
    void givenCreateTicketRequest_whenCreateOperatorUiTicket_thenDelegateToUseCase() {
        final OperatorUiCreateTaskRequestDTO requestDto = OperatorUiCreateTaskRequestDTO.builder()
                .ticket("SITIONIX-142")
                .task("task")
                .serviceIds(List.of("atmssox"))
                .build();
        final OperatorUiCreateTaskCommand command = new OperatorUiCreateTaskCommand(
                "SITIONIX-142",
                "task",
                List.of("atmssox"),
                null
        );
        final OperatorUiTaskMutationResponse response = mutationResponse("OPEN");
        final OperatorUiTaskMutationResponseDTO responseDto = mutationResponseDto("OPEN");
        when(this.forgeAiOperatorApiMapper.asOperatorUiCreateTaskCommand(requestDto)).thenReturn(command);
        when(this.manageOperatorUiTasks.create(command)).thenReturn(response);
        when(this.forgeAiOperatorApiMapper.asOperatorUiTaskMutationResponse(response)).thenReturn(responseDto);

        final ResponseEntity<OperatorUiTaskMutationResponseDTO> result = this.controller.createOperatorUiTicket(requestDto);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isSameAs(responseDto);
    }

    @Test
    void givenTicketId_whenExecuteOperatorUiTicket_thenDelegateToUseCase() {
        final OperatorUiTaskMutationResponse response = mutationResponse("READY_TO_START");
        final OperatorUiTaskMutationResponseDTO responseDto = mutationResponseDto("READY_TO_START");
        when(this.manageOperatorUiTasks.execute(TICKET_ID)).thenReturn(response);
        when(this.forgeAiOperatorApiMapper.asOperatorUiTaskMutationResponse(response)).thenReturn(responseDto);

        final ResponseEntity<OperatorUiTaskMutationResponseDTO> result = this.controller.executeOperatorUiTicket(TICKET_ID);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(responseDto);
    }

    @Test
    void givenTicketId_whenDeleteOperatorUiTicket_thenDelegateToUseCase() {
        final ResponseEntity<Void> result = this.controller.deleteOperatorUiTicket(TICKET_ID);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(this.manageOperatorUiTasks).delete(TICKET_ID);
    }

    @Test
    void givenTicketId_whenGetOperatorUiTicketGraph_thenDelegateToUseCase() {
        final OperatorUiTicketGraphResponse response = new OperatorUiTicketGraphResponse(
                TICKET_ID,
                "SITIONIX-142",
                "OPEN",
                null,
                "task",
                null,
                null,
                null,
                List.of()
        );
        final OperatorUiTicketGraphResponseDTO responseDto = OperatorUiTicketGraphResponseDTO.builder()
                .ticketId(TICKET_ID)
                .ticketKey("SITIONIX-142")
                .build();
        when(this.getOperatorUiReadModel.graph(TICKET_ID)).thenReturn(response);
        when(this.forgeAiOperatorApiMapper.asOperatorUiTicketGraphResponse(response)).thenReturn(responseDto);

        final ResponseEntity<OperatorUiTicketGraphResponseDTO> result = this.controller.getOperatorUiTicketGraph(TICKET_ID);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(responseDto);
    }

    @Test
    void givenTicketAndLaneId_whenGetOperatorUiLane_thenDelegateToUseCase() {
        final OperatorUiLaneDetailResponse response = new OperatorUiLaneDetailResponse(
                TICKET_ID,
                "SITIONIX-142",
                "OPEN",
                LANE_ID,
                "ANALYZER",
                "automationservice-sox",
                "atmssox",
                "IN_PROGRESS",
                0,
                "task",
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of()
        );
        final OperatorUiLaneDetailResponseDTO responseDto = OperatorUiLaneDetailResponseDTO.builder()
                .ticketId(TICKET_ID)
                .laneId(LANE_ID)
                .build();
        when(this.getOperatorUiReadModel.lane(TICKET_ID, LANE_ID)).thenReturn(response);
        when(this.forgeAiOperatorApiMapper.asOperatorUiLaneDetailResponse(response)).thenReturn(responseDto);

        final ResponseEntity<OperatorUiLaneDetailResponseDTO> result = this.controller.getOperatorUiLane(TICKET_ID, LANE_ID);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(responseDto);
    }

    @Test
    void givenAgentsConfigRequest_whenGetOperatorAgentConfig_thenDelegateToUseCase() {
        final OperatorAgentConfigResponse response = new OperatorAgentConfigResponse(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "restart"
        );
        when(this.manageOperatorAgentConfig.config()).thenReturn(response);

        final ResponseEntity<OperatorAgentConfigResponse> result = this.controller.getOperatorAgentConfig();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(response);
    }

    @Test
    void givenSaveResourceRequest_whenSaveOperatorAgentConfigResource_thenDelegateToUseCase() {
        final OperatorConfigResourceSaveRequest request = new OperatorConfigResourceSaveRequest("agent-yml", "agents: []");
        final OperatorConfigResourceView response = new OperatorConfigResourceView(
                "agent-yml",
                "agent.yml",
                "yaml",
                "/repo/boot/src/main/resources/agent.yml",
                true,
                "agents: []"
        );
        when(this.manageOperatorAgentConfig.saveResource(request)).thenReturn(response);

        final ResponseEntity<OperatorConfigResourceView> result = this.controller.saveOperatorAgentConfigResource(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(response);
    }

    private static OperatorUiTaskMutationResponse mutationResponse(final String status) {
        return new OperatorUiTaskMutationResponse(
                TICKET_ID,
                "SITIONIX-142",
                status,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private static OperatorUiTaskMutationResponseDTO mutationResponseDto(final String status) {
        return OperatorUiTaskMutationResponseDTO.builder()
                .ticketId(TICKET_ID)
                .ticketKey("SITIONIX-142")
                .status(status)
                .build();
    }
}
