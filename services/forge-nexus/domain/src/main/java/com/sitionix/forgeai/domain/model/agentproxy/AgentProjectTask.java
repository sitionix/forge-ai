package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentProjectTask(
        UUID id,
        UUID projectId,
        String title,
        String input,
        UUID workflowId,
        List<AgentWorkflowRunSummary> runs,
        Instant createdAt,
        Instant updatedAt
) {
}
