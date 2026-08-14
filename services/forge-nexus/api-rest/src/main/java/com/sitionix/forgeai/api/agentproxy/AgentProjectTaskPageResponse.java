package com.sitionix.forgeai.api.agentproxy;

import java.util.List;

public record AgentProjectTaskPageResponse(
        List<AgentProjectTaskSummaryResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}
