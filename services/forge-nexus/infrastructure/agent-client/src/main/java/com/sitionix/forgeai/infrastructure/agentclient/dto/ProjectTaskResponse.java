package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectTaskResponse(
        UUID id,
        UUID projectId,
        String title,
        String input,
        UUID workflowId,
        List<UUID> repositoryIds,
        List<WorkflowRunSummaryResponse> runs,
        JsonNode result,
        Instant createdAt,
        Instant updatedAt
) {
    public ProjectTaskResponse(final UUID id,
                               final UUID projectId,
                               final String title,
                               final String input,
                               final UUID workflowId,
                               final List<UUID> repositoryIds,
                               final List<WorkflowRunSummaryResponse> runs,
                               final Instant createdAt,
                               final Instant updatedAt) {
        this(id, projectId, title, input, workflowId, repositoryIds, runs, null, createdAt, updatedAt);
    }
}
