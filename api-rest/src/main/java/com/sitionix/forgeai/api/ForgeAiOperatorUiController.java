package com.sitionix.forgeai.api;

import com.app_afesox.fgaisox.api_first.api.ForgeAiOperatorUiApi;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiCreateTaskRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiLaneDetailResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiServiceCatalogResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiTaskMutationResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiTicketGraphResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiTicketListResponseDTO;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel;
import com.sitionix.forgeai.domain.usecase.ManageOperatorUiTasks;
import com.sitionix.forgeai.mapper.ForgeAiOperatorApiMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ForgeAiOperatorUiController implements ForgeAiOperatorUiApi {

    private final GetOperatorUiReadModel getOperatorUiReadModel;
    private final ManageOperatorUiTasks manageOperatorUiTasks;
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
}
