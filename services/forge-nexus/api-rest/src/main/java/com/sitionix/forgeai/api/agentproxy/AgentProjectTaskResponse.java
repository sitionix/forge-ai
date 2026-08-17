package com.sitionix.forgeai.api.agentproxy;

import com.fasterxml.jackson.databind.JsonNode;
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
        JsonNode result,
        Instant createdAt,
        Instant updatedAt
) {
    public AgentProjectTaskResponse(final UUID id,
                                    final UUID projectId,
                                    final String title,
                                    final String input,
                                    final UUID workflowId,
                                    final List<AgentWorkflowRunSummaryResponse> runs,
                                    final Instant createdAt,
                                    final Instant updatedAt) {
        this(id, projectId, title, input, workflowId, runs, null, createdAt, updatedAt);
    }
}
