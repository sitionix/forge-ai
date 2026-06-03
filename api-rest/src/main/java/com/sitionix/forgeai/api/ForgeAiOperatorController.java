package com.sitionix.forgeai.api;

import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.usecase.ManageLaneExecutions;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/forge-ai/operator/executions")
public class ForgeAiOperatorController {

    private final ManageLaneExecutions manageLaneExecutions;

    @GetMapping
    public ResponseEntity<List<OperatorExecutionResponse>> executions() {
        return ResponseEntity.ok(this.manageLaneExecutions.findActiveExecutions().stream()
                .map(this::asResponse)
                .toList());
    }

    @GetMapping("/active")
    public ResponseEntity<List<OperatorExecutionResponse>> activeExecutions() {
        return this.executions();
    }

    @GetMapping("/{executionId}")
    public ResponseEntity<OperatorExecutionResponse> execution(@PathVariable final UUID executionId) {
        return ResponseEntity.ok(this.asResponse(this.manageLaneExecutions.getExecution(executionId)));
    }

    @PostMapping("/{executionId}/interrupt")
    public ResponseEntity<OperatorExecutionResponse> interrupt(@PathVariable final UUID executionId) {
        return ResponseEntity.ok(this.asResponse(this.manageLaneExecutions.interrupt(executionId)));
    }

    private OperatorExecutionResponse asResponse(final LaneExecution execution) {
        return new OperatorExecutionResponse(
                execution.getId(),
                execution.getTicketId(),
                execution.getLaneId(),
                execution.getAgentId(),
                execution.getScope(),
                execution.getStatus() == null ? null : execution.getStatus().name(),
                execution.getProcessPid(),
                execution.getSessionId(),
                execution.getThreadId(),
                execution.getActiveTurnId(),
                execution.getCurrentStepId(),
                execution.getCurrentStepOrder(),
                execution.getCurrentStepTitle(),
                execution.getLastProgressEvent(),
                execution.getLastProgressAt(),
                "just forge-ai-stop-execution " + execution.getId(),
                execution.getStderrTail() == null ? List.of() : execution.getStderrTail()
        );
    }

    private record OperatorExecutionResponse(
            UUID executionId,
            UUID ticketId,
            UUID laneId,
            String agentId,
            String scope,
            String status,
            Long processPid,
            String codexSessionId,
            String codexThreadId,
            String activeTurnId,
            String activeStepId,
            Integer activeStepOrder,
            String activeStepTitle,
            String lastProgressEvent,
            java.time.LocalDateTime lastProgressAt,
            String stopCommand,
            List<String> stderrTail
    ) {
    }
}
