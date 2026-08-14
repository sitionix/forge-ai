package com.sitionix.forgeagent.domain.model;

import java.util.List;

public record ProjectTaskPage(
        List<ProjectTask> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}
