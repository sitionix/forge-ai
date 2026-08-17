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
        NodeRunOutput result,
        Instant createdAt,
        Instant updatedAt
) {
    public ProjectTaskDetails(final UUID id,
                              final UUID projectId,
                              final String title,
                              final String input,
                              final UUID workflowId,
                              final List<WorkflowRunSummary> runs,
                              final Instant createdAt,
                              final Instant updatedAt) {
        this(id, projectId, title, input, workflowId, runs, null, createdAt, updatedAt);
    }
}
