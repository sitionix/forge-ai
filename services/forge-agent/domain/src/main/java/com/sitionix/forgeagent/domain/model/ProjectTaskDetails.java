package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectTaskDetails(
        UUID id,
        UUID projectId,
        String title,
        String input,
        UUID workflowId,
        List<WorkflowRunSummary> runs,
        Instant createdAt,
        Instant updatedAt
) {
}
