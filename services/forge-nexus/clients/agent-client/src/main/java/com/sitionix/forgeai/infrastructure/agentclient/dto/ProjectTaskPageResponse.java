package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.List;

public record ProjectTaskPageResponse(
        List<ProjectTaskSummaryResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}
