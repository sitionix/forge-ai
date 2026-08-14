package com.sitionix.forgeagent.domain.model;

import java.util.List;

public record ProjectTaskSummaryPage(
        List<ProjectTaskSummary> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}
