package com.sitionix.forgeagent.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectTaskResponse(
        UUID id,
        UUID projectId,
        String title,
        String input,
        UUID workflowId,
        List<WorkflowRunSummaryResponse> runs,
        Instant createdAt,
        Instant updatedAt
) {
}
