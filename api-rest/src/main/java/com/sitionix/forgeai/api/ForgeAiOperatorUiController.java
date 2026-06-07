package com.sitionix.forgeai.api;

import com.app_afesox.fgaisox.api_first.api.ForgeAiOperatorUiApi;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiCreateTaskRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiLaneDetailResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiServiceCatalogResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiTaskMutationResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiTicketGraphResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiTicketListResponseDTO;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorAgentConfigResponse;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorConfigResourceSaveRequest;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig.OperatorConfigResourceView;
import com.sitionix.forgeai.domain.usecase.ManageOperatorUiTasks;
import com.sitionix.forgeai.mapper.ForgeAiOperatorApiMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ForgeAiOperatorUiController implements ForgeAiOperatorUiApi {

    private final GetOperatorUiReadModel getOperatorUiReadModel;
    private final ManageOperatorUiTasks manageOperatorUiTasks;
    private final ManageOperatorAgentConfig manageOperatorAgentConfig;
    private final ForgeAiOperatorApiMapper forgeAiOperatorApiMapper;

    @Override
    public ResponseEntity<OperatorUiTicketListResponseDTO> getOperatorUiTickets(final Integer limit) {
        return ResponseEntity.ok(this.forgeAiOperatorApiMapper.asOperatorUiTicketListResponse(
                this.getOperatorUiReadModel.tickets(limit)
        ));
    }

    @Override
    public ResponseEntity<OperatorUiServiceCatalogResponseDTO> getOperatorUiServices() {
        return ResponseEntity.ok(this.forgeAiOperatorApiMapper.asOperatorUiServiceCatalogResponse(
                this.manageOperatorUiTasks.services()
        ));
    }

    @Override
    public ResponseEntity<OperatorUiTaskMutationResponseDTO> createOperatorUiTicket(
            final OperatorUiCreateTaskRequestDTO operatorUiCreateTaskRequestDTO
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(this.forgeAiOperatorApiMapper.asOperatorUiTaskMutationResponse(
                this.manageOperatorUiTasks.create(this.forgeAiOperatorApiMapper.asOperatorUiCreateTaskCommand(
                        operatorUiCreateTaskRequestDTO
                ))
        ));
    }

    @Override
    public ResponseEntity<OperatorUiTaskMutationResponseDTO> executeOperatorUiTicket(final UUID ticketId) {
        return ResponseEntity.ok(this.forgeAiOperatorApiMapper.asOperatorUiTaskMutationResponse(
                this.manageOperatorUiTasks.execute(ticketId)
        ));
    }

    @Override
    public ResponseEntity<Void> deleteOperatorUiTicket(final UUID ticketId) {
        this.manageOperatorUiTasks.delete(ticketId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<OperatorUiTicketGraphResponseDTO> getOperatorUiTicketGraph(final UUID ticketId) {
        return ResponseEntity.ok(this.forgeAiOperatorApiMapper.asOperatorUiTicketGraphResponse(
                this.getOperatorUiReadModel.graph(ticketId)
        ));
    }

    @Override
    public ResponseEntity<OperatorUiLaneDetailResponseDTO> getOperatorUiLane(final UUID ticketId, final UUID laneId) {
        return ResponseEntity.ok(this.forgeAiOperatorApiMapper.asOperatorUiLaneDetailResponse(
                this.getOperatorUiReadModel.lane(ticketId, laneId)
        ));
    }

    @GetMapping("/api/v1/forge-ai/operator/ui/agents/config")
    public ResponseEntity<OperatorAgentConfigResponse> getOperatorAgentConfig() {
        return ResponseEntity.ok(this.manageOperatorAgentConfig.config());
    }

    @PutMapping("/api/v1/forge-ai/operator/ui/agents/config/resources")
    public ResponseEntity<OperatorConfigResourceView> saveOperatorAgentConfigResource(
            @RequestBody final OperatorConfigResourceSaveRequest request
    ) {
        return ResponseEntity.ok(this.manageOperatorAgentConfig.saveResource(request));
    }
}
