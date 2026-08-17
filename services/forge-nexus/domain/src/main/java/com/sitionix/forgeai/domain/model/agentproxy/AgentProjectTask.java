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
        AgentNodeRunOutputDocument result,
        Instant createdAt,
        Instant updatedAt
) {
    public AgentProjectTask(final UUID id,
                            final UUID projectId,
                            final String title,
                            final String input,
                            final UUID workflowId,
                            final List<AgentWorkflowRunSummary> runs,
                            final Instant createdAt,
                            final Instant updatedAt) {
        this(id, projectId, title, input, workflowId, runs, null, createdAt, updatedAt);
    }
}
