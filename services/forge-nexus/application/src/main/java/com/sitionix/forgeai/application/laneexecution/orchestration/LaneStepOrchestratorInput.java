package com.sitionix.forgeai.application.laneexecution.orchestration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LaneStepOrchestratorInput(
        UUID ticketId,
        String ticketKey,
        UUID laneId,
        String agentId,
        String scope,
        String serviceId,
        String stepId,
        String handler,
        List<Map<String, Object>> tasks,
        Map<String, Object> scopeContext,
        Map<String, Object> previousEvidence,
        Map<String, Map<String, Object>> stepEvidence
) {
}
