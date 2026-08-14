package com.sitionix.forgeagent.api.dto;

import java.util.List;

public record ProjectTaskPageResponse(
        List<ProjectTaskSummaryResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}
