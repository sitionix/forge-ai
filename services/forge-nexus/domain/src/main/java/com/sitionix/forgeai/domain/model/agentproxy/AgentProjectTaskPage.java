package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;

public record AgentProjectTaskPage(
        List<AgentProjectTaskSummary> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}
