package com.sitionix.forgeai.api.agentproxy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentProjectTaskResponse(
        UUID id,
        UUID projectId,
        String title,
        String input,
        UUID workflowId,
        List<AgentWorkflowRunSummaryResponse> runs,
        Instant createdAt,
        Instant updatedAt
) {
}
