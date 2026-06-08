package com.sitionix.forgeai.application.laneexecution.orchestration;

import com.sitionix.forgeai.domain.model.codex.ScopeContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ApiArtifactGenerationOrchestratorInput(
        UUID ticketId,
        String ticketKey,
        UUID laneId,
        String agentId,
        String scope,
        String serviceId,
        String stepId,
        String handler,
        List<ApiArtifactGenerationTask> tasks,
        ScopeContext scopeContext,
        Map<String, Object> previousEvidence,
        Map<String, Map<String, Object>> stepEvidence
) {
}
