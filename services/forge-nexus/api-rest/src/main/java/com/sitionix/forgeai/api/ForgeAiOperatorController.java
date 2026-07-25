package com.sitionix.forgeai.api;

import com.app_afesox.fgaisox.api_first.api.ForgeAiOperatorExecutionApi;
import com.app_afesox.fgaisox.api_first.dto.OperatorExecutionDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorExecutionsResponseDTO;
import com.sitionix.forgeai.domain.usecase.ManageLaneExecutions;
import com.sitionix.forgeai.mapper.ForgeAiOperatorApiMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ForgeAiOperatorController implements ForgeAiOperatorExecutionApi {

    private final ManageLaneExecutions manageLaneExecutions;
    private final ForgeAiOperatorApiMapper forgeAiOperatorApiMapper;

    @Override
    public ResponseEntity<OperatorExecutionsResponseDTO> getOperatorExecutions() {
        return ResponseEntity.ok(this.forgeAiOperatorApiMapper.asOperatorExecutionsResponse(
                this.manageLaneExecutions.findActiveExecutions().stream()
                        .map(this.forgeAiOperatorApiMapper::asOperatorExecution)
                        .toList()
        ));
    }

    @Override
    public ResponseEntity<OperatorExecutionsResponseDTO> getActiveOperatorExecutions() {
        return this.getOperatorExecutions();
    }

    @Override
    public ResponseEntity<OperatorExecutionDTO> getOperatorExecution(final UUID executionId) {
        return ResponseEntity.ok(this.forgeAiOperatorApiMapper.asOperatorExecution(
                this.manageLaneExecutions.getExecution(executionId)
        ));
    }

    @Override
    public ResponseEntity<OperatorExecutionDTO> interruptOperatorExecution(final UUID executionId) {
        return ResponseEntity.ok(this.forgeAiOperatorApiMapper.asOperatorExecution(
                this.manageLaneExecutions.interrupt(executionId)
        ));
    }
}
